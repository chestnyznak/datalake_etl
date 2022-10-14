#!/usr/bin/env bash
REVISION=$1
PREV_RES=0
export JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-17.jdk/Contents/Home
echo ">>>>>>>>>>>>>>>>>>>>>>>> Given revision is ${REVISION}"

# parent multimodule artifact
mvn clean install -U -N -Drevision=$REVISION

PREV_RES=$?

if [[ $PREV_RES -eq 0 ]]; then
  cd ./common || exit 69
  mvn clean install -Drevision=$REVISION
  PREV_RES=$?
  if [[ $PREV_RES -eq 0 ]]; then
    echo ">>>>>>>>>>>>>>>>>>>>>>>> Installed common $REVISION"
  else
    echo ">>>>>>>>>>>>>>>>>>>>>>>> Unable to install common res = $PREV_RES"
  fi
fi

if [[ $PREV_RES -eq 0 ]]; then
  cd ../common-apps || exit 69
  mvn clean package -Drevision=$REVISION
  PREV_RES=$?
  if [[ $PREV_RES -eq 0 ]]; then
    echo ">>>>>>>>>>>>>>>>>>>>>>>> Installed common-apps $REVISION"
  else
    echo ">>>>>>>>>>>>>>>>>>>>>>>> Unable to install common-apps res = $PREV_RES"
  fi
fi