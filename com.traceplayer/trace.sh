#!/bin/sh
sudo mvn clean
sudo mvn compile
sudo mvn exec:java -Dexec.mainClass=com.traceplayer.TracePlayer -Dexec.args="http://ec2-54-85-254-134.compute-1.amazonaws.com traces/trace4.txt" -Dlog4j.configurationFile=/home/ubuntu/15618final/com.traceplayer/src/main/resources/log4j2.xml

