#!/bin/sh
cd /home/ubuntu/15618final/com.server
=======
sudo service memcached restart
sudo mvn clean
sudo mvn compile
sudo mvn exec:java -Dexec.mainClass=com.server.WebServer -Dexec.args="-Xms2048m -Xmx5120m -XX:PermSize=1024m" -Dlog4j.configurationFile=/home/ubuntu/15618final/com.server/src/main/resources/log4j2.xml
