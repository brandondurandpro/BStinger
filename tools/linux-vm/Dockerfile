# Image of the Linux test containers of ../../linux-vm: the official Ubuntu image with the packages that the
# test-linux job of .github/workflows/ci.yml installs (keep both lists in step). Built once per Ubuntu version.
ARG UBUNTU=24.04
FROM ubuntu:${UBUNTU}

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    KOTLIN_CLI_NO_WELCOME_BANNER=1

# The 20.04 repositories may have moved to old-releases: the sources are switched over if the update fails.
RUN { apt-get update || { \
        sed -i \
            -e 's|http://archive.ubuntu.com/ubuntu|http://old-releases.ubuntu.com/ubuntu|g' \
            -e 's|http://security.ubuntu.com/ubuntu|http://old-releases.ubuntu.com/ubuntu|g' \
            /etc/apt/sources.list \
        && apt-get update; }; } \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl git tar unzip xz-utils openssh-client fish tcsh \
        xvfb xauth x11-utils xwayland weston \
        libgl1 libx11-6 libxext6 libxrender1 libxtst6 libxi6 \
        fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*
