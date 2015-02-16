package com.asan.client;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.apache.log4j.Logger;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

public class Client {

    final static Logger logger = Logger.getLogger(Client.class);


    public static void main(String[] args) throws Exception {

        Properties prop = new Properties();
        InputStream properties = new FileInputStream("config.properties");

        prop.load(properties);

        String server = prop.getProperty("server");
        int port = Integer.parseInt(prop.getProperty("port"));
        int threadCount = Integer.parseInt(prop.getProperty("threadCount"));
        int connectionCount = Integer.parseInt(prop.getProperty("connectionCount"));

        logger.info("program starts");

        EventLoopGroup workerGroup = new NioEventLoopGroup(threadCount);

        ClientConnection[] connections = new ClientConnection[connectionCount];
        for(int i=0; i< connectionCount; i++) {
            connections[i] = new ClientConnection();
            connections[i].connect(workerGroup, server,port,i);
        }

        for(int i = 0 ; i<connectionCount; i++)
            connections[i].send();

        Thread.sleep(10000);
        workerGroup.shutdownGracefully();

    }
}