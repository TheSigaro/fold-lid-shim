# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk AS build

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="${PATH}:/opt/android-sdk/cmdline-tools/latest/bin"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ARG CMDLINE_TOOLS=11076708
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl -fsSL -o /tmp/cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

RUN (yes || true) | sdkmanager --licenses > /dev/null \
    && sdkmanager "platforms;android-36" "build-tools;36.0.0"

WORKDIR /src
COPY . .
RUN mkdir -p /root/.android && cp debug.keystore /root/.android/debug.keystore
RUN chmod +x gradlew && ./gradlew --no-daemon assembleDebug

FROM scratch AS apk
COPY --from=build /src/app/build/outputs/apk/debug/app-debug.apk /app-debug.apk
