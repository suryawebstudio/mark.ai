#!/bin/bash
# Android SDK Setup Script for MARK Project
# Run this script to install required Android SDK components

set -e

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

echo "Setting up Android SDK at $ANDROID_HOME"

# Install command-line tools if not present
if [ ! -d "$ANDROID_HOME/cmdline-tools/latest/bin" ]; then
    echo "Installing command-line tools..."
    cd /tmp
    wget -q --no-check-certificate https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
    mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
    unzip -o cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools/latest"
    # Fix directory structure (sometimes nested)
    if [ -d "$ANDROID_HOME/cmdline-tools/latest/cmdline-tools" ]; then
        mv "$ANDROID_HOME/cmdline-tools/latest/cmdline-tools/"* "$ANDROID_HOME/cmdline-tools/latest/"
        rmdir "$ANDROID_HOME/cmdline-tools/latest/cmdline-tools"
    fi
fi

export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

# Accept licenses
echo "Accepting SDK licenses..."
yes | sdkmanager --licenses

# Install required components
echo "Installing Android SDK Platform 34..."
sdkmanager "platforms;android-34"

echo "Installing Android Build Tools 34.0.0..."
sdkmanager "build-tools;34.0.0"

echo "Installing Platform Tools..."
sdkmanager "platform-tools"

# Verify installation
echo "Verifying installation..."
sdkmanager --list | grep -E "(android-34|build-tools;34)"

# Create local.properties
echo "sdk.dir=$ANDROID_HOME" > local.properties

echo "Android SDK setup complete!"
echo "Run 'source ~/.bashrc' or restart terminal to update PATH"
echo "Then run: ./gradlew assembleDebug --no-daemon"
