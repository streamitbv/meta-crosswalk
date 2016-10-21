require chromium.inc
require chromium-unbundle.inc
require gn-utils.inc

inherit qemu

OUTPUT_DIR = "out/Release"
B = "${S}/${OUTPUT_DIR}"

SRC_URI += " \
        file://0001-Build-error-in-chrome-browser-extensions-api-tabs-ta.patch \
        file://0001-Fix-Chrome-ARM64-linux-compilation-errors.patch \
        file://v8-qemu-wrapper.patch \
        "

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

	# ./build/linux/unbundle/remove_bundled_libraries.py ${THIRD_PARTY_TO_PRESERVE}
	./build/linux/unbundle/replace_gn_files.py --system-libraries ${GN_UNBUNDLE_LIBS}

	gn gen --args='${GN_ARGS}' "${OUTPUT_DIR}"
}

do_compile() {
	ninja -v chrome chrome_sandbox -j42
}

do_install() {
	install -d ${D}${bindir}
	install -d ${D}${libdir}/chromium
	install -d ${D}${libdir}/chromium/locales

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

# FILES_${PN} = "${bindir}/xwalk ${libdir}/xwalk/*"
# FILES_${PN}-dbg = "${bindir}/.debug/ ${libdir}/xwalk/.debug/"
# PACKAGE_DEBUG_SPLIT_STYLE = "debug-without-src"

INHIBIT_PACKAGE_DEBUG_SPLIT = "1"
