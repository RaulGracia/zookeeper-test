# Zookeeper SSL Issue investigation

This repo is intended to make it easier to debug the problems to configure SSL in a embedded Zookeeper server. In particular, it contains:

- src/ZooKeeperServiceRunner: This is the a class extracted from the Pravega repository used to run Pravega standalone (and standalone tests). 
The problem we are observing is related to start a Zookeeper server process in this class and configuring SSL. 

- logs: Contains logs for Zookeeper clients and servers in 2 scenarios: i) using the regular zkServer.sh script with SSL enabled, and ii)
using the ZookeeperServiceRunner class with SSL enabled. In both cases, we used the regular zkServer.sh to access the Zookeeper server.

# Getting Started

## Starting the ZookeeperServiceRunner with SSL

To execute the code of this repo, you just need to:

1) Install locally zookeeper-3.5.5:
```
git clone -b branch-3.5.5 https://github.com/apache/zookeeper
cd zookeeper
mvn install
```

2) Build the executable tu run the ZooKeeperServiceRunner easily:
```
git clone https://github.com/RaulGracia/zookeeper-test
cd zookeeper-test
./gradlew distTar
cd build/distribution
```
In that folder you will see the produced artifact.

3) Unzip the artifact and execute the ZooKeeperServiceRunner:
```
tar -xvf zookeeper-test.tar
cd zookeeper-test
./bin/zookeeper-test true 2281 config/server.keystore.jks 1111_aaaa config/client.truststore.jks 1111_aaaa
```

Note that we provide sample trustStore and keyStore files in the `config` dir of this project.

## Starting the Regular Zookeeper Server (zkServer.sh) with SSL

If you want to test how `zkServer.sh` is working with these very same security settings, go to the zookeeper-3.5.5 folder:

1) Add the appropriate environment variable to your environment:
```
export SERVER_JVMFLAGS="
-Dzookeeper.serverCnxnFactory=org.apache.zookeeper.server.NettyServerCnxnFactory
-Dzookeeper.ssl.keyStore.location=/config/server.keystore.jks 
-Dzookeeper.ssl.keyStore.password=1111_aaaa 
-Dzookeeper.ssl.trustStore.location=/config/client.truststore.jks
-Dzookeeper.ssl.trustStore.password=1111_aaaa" 
``` 
And add the `secureClientPort=2281` property in your `conf/zoo.cfg` file.

2) Execute the server:
```
zkServer.sh -start
```

## Start the zkCli.sh

Irrespective on how you run the server, for running the Zookeeper client you first need to:

1) Add the appropriate environment variables to your environment:
```
export CLIENT_JVMFLAGS="
-Dzookeeper.clientCnxnSocket=org.apache.zookeeper.ClientCnxnSocketNetty 
-Dzookeeper.client.secure=true 
-Dzookeeper.ssl.keyStore.location=config/server.keystore.jks 
-Dzookeeper.ssl.keyStore.password=1111_aaaa 
-Dzookeeper.ssl.trustStore.location=config/client.truststore.jks 
-Dzookeeper.ssl.trustStore.password=1111_aaaa"
``` 

2) Execute the client:
```
zkCli.sh -server localhost:2281
```

