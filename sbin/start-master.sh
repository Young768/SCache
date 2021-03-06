#!/bin/bash

usage() {
    echo "Usage: start-master"
    exit 1
}

sbin=`dirname "$0"`
sbin=`cd "$sbin"; pwd`

. "$sbin/config.sh"


JAR=${SCACHE_HOME}/target/scala-2.10/SCache-assembly-0.1-SNAPSHOT.jar

nohup java -cp $JAR org.scache.deploy.ScacheMaster >/dev/null 2>&1 &
