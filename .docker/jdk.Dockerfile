FROM eclipse-temurin:21-jdk

# X11 + font + GTK libs PhpStorm needs to render its Swing UI under
# `./gradlew runIde`.  The base image ships headless deps only; without
# these the JVM boots but dies on the first Toolkit.getDefaultToolkit()
# call with "Can't connect to X11 window server" or a font-config error.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        libxext6 \
        ibxrender1 \
        ibxi6 \
        ibxtst6 \
        ibxrandr2 \
        ibxcursor1 \
        libxinerama1 \
        ibfontconfig1 \
        ibfreetype6 \
        ibgtk-3-0 \
        fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*
