#!/bin/sh
#
#    Licensed to the Apache Software Foundation (ASF) under one or more
#    contributor license agreements.  See the NOTICE file distributed with
#    this work for additional information regarding copyright ownership.
#    The ASF licenses this file to You under the Apache License, Version 2.0
#    (the "License"); you may not use this file except in compliance with
#    the License.  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.
#

# Ensure LANG is configured
if [ -f /etc/rc.d/init.d/functions ]; then
    . /etc/rc.d/init.d/functions
fi

# The java implementation to use.
#export JAVA_HOME=/usr/java/jdk1.8.0/

export NIFI_HOME=$(cd "${SCRIPT_DIR}" && cd .. && pwd)

#The directory for the NiFi pid file
if [ -d /var/run/nifi ]; then
    export NIFI_PID_DIR=/var/run/nifi
else
    export NIFI_PID_DIR="${NIFI_HOME}/run"
fi

#The directory for NiFi log files
if [ -d /var/log/nifi ]; then
    export NIFI_LOG_DIR=/var/log/nifi
else
    export NIFI_LOG_DIR="${NIFI_HOME}/logs"
fi
