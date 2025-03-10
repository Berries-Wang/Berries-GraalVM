#!/bin/bash
export MX_PATH=/Users/wang/WorkSpace/OpenSource/mx
export JAVA_HOME=/Users/wang/.mx/jdks/labsjdk-ce-21-debug-jvmci-23.1-b33/Contents/Home
export PATH=$MX_PATH:$JAVA_HOME/bin:$PATH

mx --env ce  build


mx --env ce  graalvm-home

