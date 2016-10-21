require chromium.inc
require chromium-unbundle.inc

OUTPUT_DIR = "out/Release"
B = "${S}/${OUTPUT_DIR}"

SRC_URI += " \
        file://0001-Build-error-in-chrome-browser-extensions-api-tabs-ta.patch \
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
        target_cpu="${@gn_arch_name(d)}" \
        '

# This function translates between Yocto's TUNE_ARCH values and the ones
# expected by GN.
def gn_arch_name(d):
    arch_equivalences = {
        'aarch64': 'arm64',
        'arm': 'arm',
        'i586': 'x86',
        'x86_64': 'x64',
    }
    tune_arch = d.getVar("TUNE_ARCH", True)
    try:
        return arch_equivalences[tune_arch]
    except KeyError:
        bb.msg.fatal("Unknown TUNE_ARCH value.")

do_configure() {
	mkdir -p ${S}/build/toolchain/yocto
	cat > ${S}/build/toolchain/yocto/BUILD.gn <<EOF
 import("//build/config/sysroot.gni")
 import("//build/toolchain/gcc_toolchain.gni")
 gcc_toolchain("yocto_native") {
   cxx = "${BUILD_CXX}"
   cc = "${BUILD_CC}"
   ar = "${BUILD_AR}"
   ld = cxx
   nm = "${BUILD_NM}"
   readelf = "${BUILD_PREFIX}readelf"
   extra_cflags = "${BUILD_CFLAGS}"
   extra_cppflags = "${BUILD_CPPFLAGS}"
   extra_cxxflags = "${BUILD_CXXFLAGS}"
   extra_ldflags = "${BUILD_LDFLAGS}"
   toolchain_args = {
     current_cpu = "x64"
     current_os = "linux"
     is_clang = false
   }
 }
 gcc_toolchain("yocto_target") {
   cxx = "${CXX}"
   cc = "${CC}"
   ar = "${AR}"
   ld = cxx
   nm = "${NM}"
   readelf = "${TARGET_PREFIX}readelf"
   extra_cflags = "${TARGET_CFLAGS}"
   extra_cppflags = "${TARGET_CPPFLAGS}"
   extra_cxxflags = "${TARGET_CXXFLAGS} -Wno-strict-overflow"
   extra_ldflags = "${TARGET_LDFLAGS}"
   toolchain_args = {
     current_cpu = "${@gn_arch_name(d)}"
     current_os = "linux"
     is_clang = false
   }
 }
EOF

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
