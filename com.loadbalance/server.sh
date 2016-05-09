#!/bin/sh
export AWS_CREDENTIAL_PROFILES_FILE=/home/ubuntu/.aws/credentials
sudo mvn clean
sudo mvn compile
sudo mvn exec:java -Dexec.mainClass=com.loadbalance.LoadBalancer -Dexec.args="/home/ubuntu/15618final/com.loadbalance/src/main/resources/scaleConfig3.json" -Dlog4j.configurationFile=/home/ubuntu/15618final/com.loadbalance/src/main/resources/log4j2.xml
