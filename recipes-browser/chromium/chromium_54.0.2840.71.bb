require chromium.inc
require chromium-unbundle.inc
require gn-utils.inc

inherit gtk-icon-cache qemu

OUTPUT_DIR = "out/Release"
B = "${S}/${OUTPUT_DIR}"

SRC_URI += " \
        file://0001-Build-error-in-chrome-browser-extensions-api-tabs-ta.patch \
        file://0001-Fix-Chrome-ARM64-linux-compilation-errors.patch \
        file://v8-qemu-wrapper.patch \
        "

# At the moment, this recipe has only been tested on i586, x86-64, ARMv6,
# ARMv7a and aarch64.
COMPATIBLE_MACHINE = "(-)"
COMPATIBLE_MACHINE_aarch64 = "(.*)"
COMPATIBLE_MACHINE_armv6 = "(.*)"
COMPATIBLE_MACHINE_armv7a = "(.*)"
COMPATIBLE_MACHINE_x86 = "(.*)"
COMPATIBLE_MACHINE_x86-64 = "(.*)"

DEPENDS = "\
    alsa-lib \
    atk \
    bison-native \
    dbus \
    expat \
    flac \
    fontconfig \
    freetype \
    glib-2.0 \
    gn-native \
    gtk+ \
    harfbuzz \
    jpeg \
    libevent \
    libwebp \
    libx11 \
    libxcomposite \
    libxcursor \
    libxdamage \
    libxext \
    libxfixes \
    libxi \
    libxml2 \
    libxrandr \
    libxrender \
    libxscrnsaver \
    libxslt \
    libxtst \
    ninja-native \
    nspr \
    nss \
    pango \
    pciutils \
    pkgconfig-native \
    ${@bb.utils.contains('DISTRO_FEATURES', 'pulseaudio', 'pulseaudio', '', d)} \
    qemu-native \
    virtual/libgl \
    "
DEPENDS_append_x86 = "yasm-native"
DEPENDS_append_x86-64 = "yasm-native"

# The wrapper script we use from upstream requires bash.
RDEPENDS_${PN} = "bash"

# The generated debug chrome binary is too big (~5Gb) for 32-bit systems.
# binutils, file and other utilities are unable to read it correctly and
# extract the debugging symbols from it.
TARGET_CFLAGS_remove_i586 = "${DEBUG_FLAGS}"
TARGET_CFLAGS_remove_armv6 = "${DEBUG_FLAGS}"
TARGET_CFLAGS_remove_armv7a = "${DEBUG_FLAGS}"

# Base GN arguments, mostly related to features we want to enable or disable.
GN_ARGS = "\
        is_debug=false \
        use_cups=false \
        use_gconf=false \
        use_gnome_keyring=false \
        use_kerberos=false \
        use_pulseaudio=${@bb.utils.contains('DISTRO_FEATURES', 'pulseaudio', 'true', 'false', d)} \
        use_system_libjpeg=true \
        "

# NaCl support depends on the NaCl toolchain that needs to be built before NaCl
# itself. The whole process is quite cumbersome so we just disable the whole
# thing for now.
GN_ARGS += "enable_nacl=false"

# We do not want to use Chromium's own Debian-based sysroots, it is easier to
# just let Chromium's build system assume we are not using a sysroot at all and
# let Yocto handle everything.
GN_ARGS += "use_sysroot=false"

# Toolchains we will use for the build. We need to point to the toolchain file
# we've created, set the right target architecture and make sure we are not
# using Chromium's toolchain (bundled clang, bundled binutils etc).
GN_ARGS += '\
        custom_toolchain="//build/toolchain/yocto:yocto_target" \
        gold_path="" \
        host_toolchain="//build/toolchain/yocto:yocto_native" \
        is_clang=false \
        linux_use_bundled_binutils=false \
        target_cpu="${@gn_arch_name('${TUNE_ARCH}')}" \
        v8_snapshot_toolchain="//build/toolchain/yocto:yocto_target" \
        '

# ARM builds need special additional flags (see ${S}/build/config/arm.gni).
ARM_FLOAT_ABI = "${@bb.utils.contains('TUNE_FEATURES', 'callconvention-hard', 'hard', 'softfp', d)}"
GN_ARGS_append_armv6 = ' arm_version=6 arm_float_abi="${ARM_FLOAT_ABI}"'
GN_ARGS_append_armv7a = ' arm_version=7 arm_float_abi="${ARM_FLOAT_ABI}"'
# tcmalloc's atomicops-internals-arm-v6plus.h uses the "dmb" instruction that
# is not available on (some?) ARMv6 models, which causes the build to fail.
GN_ARGS_append_armv6 += 'use_allocator="none"'
# The WebRTC code fails to build on ARMv6 when NEON is enabled.
# https://bugs.chromium.org/p/webrtc/issues/detail?id=6574
GN_ARGS_append_armv6 += 'arm_use_neon=false'

# V8's JIT infrastructure requires binaries such as mksnapshot and
# mkpeephole to be run in the host during the build. However, these
# binaries must have the same bit-width as the target (e.g. a x86_64
# host targeting ARMv6 needs to produce a 32-bit binary). Instead of
# depending on a third Yocto toolchain, we just build those binaries
# for the target and run them on the host with QEMU.
python do_create_v8_qemu_wrapper () {
    """Creates a small wrapper that invokes QEMU to run some target V8 binaries
    on the host."""
    qemu_libdirs = [d.expand('${STAGING_DIR_HOST}${libdir}'),
                    d.expand('${STAGING_DIR_HOST}${base_libdir}')]
    qemu_cmd = qemu_wrapper_cmdline(d, d.getVar('STAGING_DIR_HOST', True),
                                    qemu_libdirs)
    wrapper_path = d.expand('${B}/v8-qemu-wrapper.sh')
    with open(wrapper_path, 'w') as wrapper_file:
        wrapper_file.write("""#!/bin/sh

# This file has been generated automatically.
# It invokes QEMU to run binaries built for the target in the host during the
# build process.

%s "$@"
""" % qemu_cmd)
    os.chmod(wrapper_path, 0755)
}
addtask create_v8_qemu_wrapper after do_patch before do_configure

python do_write_toolchain_file () {
    """Writes a BUILD.gn file for Yocto detailing its toolchains."""
    toolchain_dir = d.expand("${S}/build/toolchain/yocto")
    bb.utils.mkdirhier(toolchain_dir)
    toolchain_file = os.path.join(toolchain_dir, "BUILD.gn")
    write_toolchain_file(d, toolchain_file)
}
addtask write_toolchain_file after do_patch before do_configure

do_configure() {
	cd ${S}
	./build/linux/unbundle/replace_gn_files.py --system-libraries ${GN_UNBUNDLE_LIBS}
	gn gen --args='${GN_ARGS}' "${OUTPUT_DIR}"
}

do_compile() {
	ninja -v "${PARALLEL_MAKE}" chrome chrome_sandbox
}

do_install() {
	install -d ${D}${bindir}
	install -d ${D}${datadir}
	install -d ${D}${datadir}/applications
	install -d ${D}${datadir}/icons
	install -d ${D}${datadir}/icons/hicolor
	install -d ${D}${libdir}/chromium
	install -d ${D}${libdir}/chromium/locales

	# Process and install Chromium's template .desktop file.
	sed -e "s,@@MENUNAME@@,Chromium Browser,g" \
	    -e "s,@@PACKAGE@@,chromium,g" \
	    -e "s,@@USR_BIN_SYMLINK_NAME@@,chromium,g" \
	    ${S}/chrome/installer/linux/common/desktop.template > chromium.desktop
	install -m 0644 chromium.desktop ${D}${datadir}/applications

	# Install icons.
	for size in 22 24 48 64 128 256; do
		install -d ${D}${datadir}/icons/hicolor/${size}x${size}
		install -d ${D}${datadir}/icons/hicolor/${size}x${size}/apps
		install -m 0644 \
			${S}/chrome/app/theme/chromium/product_logo_${size}.png \
			${D}${datadir}/icons/hicolor/${size}x${size}/apps/chromium.png
	done

	# A wrapper for the proprietary Google Chrome version already exists.
	# We can just use that one instead of reinventing the wheel.
	WRAPPER_FILE=${S}/chrome/installer/linux/common/wrapper
	sed -e "s,@@CHANNEL@@,stable,g" \
		-e "s,@@PROGNAME@@,chromium-bin,g" \
		${WRAPPER_FILE} > chromium-wrapper
	install -m 0755 chromium-wrapper ${D}${libdir}/chromium/chromium-wrapper
	ln -s ${libdir}/chromium/chromium-wrapper ${D}${bindir}/chromium

	install -m 4755 chrome_sandbox ${D}${libdir}/chromium/chrome-sandbox
	install -m 0755 chrome ${D}${libdir}/chromium/chromium-bin
	install -m 0644 *.bin ${D}${libdir}/chromium/
	install -m 0644 chrome_*.pak ${D}${libdir}/chromium/
	install -m 0644 icudtl.dat ${D}${libdir}/chromium/icudtl.dat
	install -m 0644 resources.pak ${D}${libdir}/chromium/resources.pak

	install -m 0644 locales/*.pak ${D}${libdir}/chromium/locales/
}

FILES_${PN} = " \
        ${bindir}/${PN} \
        ${datadir}/applications/${PN}.desktop \
        ${datadir}/icons/hicolor/*x*/apps/chromium.png \
        ${libdir}/${PN}/* \
        "
FILES_${PN}-dbg = "${libdir}/${PN}/.debug/"
PACKAGE_DEBUG_SPLIT_STYLE = "debug-without-src"

# There is no need to ship empty -dev packages.
ALLOW_EMPTY_${PN}-dev = "0"
