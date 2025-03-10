#!/bin/bash
export MX_PATH=/home/wei/OPEN_SOURCE/GraalVM_MX
export JAVA_HOME=/home/wei/.mx/jdks/labsjdk-ce-21-jvmci-23.1-b33
export PATH=$MX_PATH:$JAVA_HOME/bin:$PATH

mx --env ce  build

mx --env ce  graalvm-home

