package com.asan.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.ScheduledFuture;
import org.apache.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@ChannelHandler.Sharable
public class ClientConnection extends ChannelInboundHandlerAdapter {

    volatile SocketChannel socketChannel;
    int clientId;
    String serverAddr;
    int serverPort;
    ScheduledFuture<?> scheduledFuture = null;

    public static int receivedPackets = 0;
    public static int sentPackets = 0;
    public static int totalMessagesSend = 0;

    private int trySendCount = 10;
    private int tryConnectCount = 2;
    private EventLoopGroup group;
    byte[] msg;
    int sleepTime = 0;

    public static int totalSends = 0;

    private ByteBuf buf;


    final static Logger logger = Logger.getLogger(ClientConnection.class);

    public void init(EventLoopGroup loopGroup, String server, int port, int id, int sleepTime) {
        group = loopGroup;
        clientId = id;
        serverAddr = server;
        serverPort = port;
        Random random = new Random();
        tryConnectCount = random.nextInt(5);
        this.sleepTime = sleepTime;
        connect();
    }

    public void connect() {
        Random random = new Random();
        //trySendCount = random.nextInt(10);

        totalSends += trySendCount;

        Bootstrap b = new Bootstrap();
        b.group(group);

        b.channel(NioSocketChannel.class);
        b.option(ChannelOption.SO_KEEPALIVE, true);

        b.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(ClientConnection.this);
            }
        });

        b.connect(serverAddr, serverPort).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    logger.info("operationComplete : future result is successful" + clientId);
                    socketChannel = (SocketChannel) future.channel();
                    send();
                } else {
                    logger.error("operationComplete : future result is not successful");
                }
            }
        });
    }

    public void send() {
        if (socketChannel != null && socketChannel.isActive()) {
            byte[] sendMsg = generateMessage();
            ByteBuf buf = socketChannel.alloc().buffer(sendMsg.length);
            buf.writeBytes(sendMsg);
            socketChannel.writeAndFlush(buf);
            totalMessagesSend++;
        }
    }

    private byte[] generateMessage() {

        Random random = new Random();
        int rand = random.nextInt(900);
        int doGetResp = random.nextInt(10) + 2; //todo remove this +2

        byte sendResponse = 0;

        if (doGetResp > 1) {
            sentPackets++;
            sendResponse = 1;
        }

        msg = new byte[rand];
        new Random().nextBytes(msg);

        byte[] dest = new byte[2];
        dest[0] = (byte) 396;
        dest[1] = (byte) 124;
        byte[] source = new byte[2];
        Arrays.fill(source, (byte) 0);

        ByteBuffer index = ByteBuffer.allocate(4);
        index.putInt(clientId);

        byte[] clientIndex = index.array();

        int length = msg.length + 10;
        byte[] len = DecToBCDArray(length, 2);

        byte[] completeMsg = new byte[length + 2];

        for (int i = 0; i < completeMsg.length; ++i) {
            completeMsg[i] = i < 2 ? len[i] : (i < 3 ? 60 : (i < 5 ? dest[i - 3] : (i < 7 ? source[i - 5] : (i < 11 ? clientIndex[i - 7] : (i < 12 ? sendResponse : msg[i - 12])))));
        }
        return completeMsg;
    }

    int currentLen = 0;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object inputMsg) throws Exception {

        if (buf == null) {
            buf = ctx.alloc().buffer(10000);
        }
        ByteBuf m = (ByteBuf) inputMsg;
        buf.writeBytes(m);
        m.release();

        while (true) {
            if (currentLen == 0 && buf.readableBytes() > 1) {
                byte[] b = new byte[2];
                b[0] = buf.readByte();
                b[1] = buf.readByte();
                currentLen = Integer.parseInt(BCDtoString(b));
            }
            if (buf.readableBytes() >= currentLen && currentLen > 0) {
                Random random = new Random();
                try {
                    buf.readByte();
                    buf.readByte();
                    buf.readByte();
                    buf.readByte();
                    buf.readByte();

                    byte[] bytes = new byte[4];

                    bytes[0] = buf.readByte();
                    bytes[1] = buf.readByte();
                    bytes[2] = buf.readByte();
                    bytes[3] = buf.readByte();

                    int messageID = bytes[0] << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);

                    buf.readByte(); //this is for send response byte

                    byte[] inputMessage = new byte[currentLen - 10];

                    int i = 0;
                    while (i < inputMessage.length) {
                        inputMessage[i] = buf.readByte();
                        i++;
                    }
                    receivedPackets++;
                    if (!Arrays.equals(msg, inputMessage)) {
                        logger.error("input is not equal output" + msg[0] + " , " + inputMessage[0]);
                    }

                    if (clientId != messageID)
                        logger.error("ClientId " + clientId + "is not equal messageId" + messageID);

                    int waitForSend = random.nextInt(sleepTime) + 100;

                    if (trySendCount > 0) {
                        scheduledFuture = ctx.executor().schedule(runSend, waitForSend, TimeUnit.MILLISECONDS);
                    }
                    trySendCount--;

                } catch (Exception ex) {
                    logger.error("channelRead ", ex);
                } finally {

                    if (tryConnectCount > 0 && trySendCount == 0) {
                        int waitForConnect = random.nextInt(sleepTime) + 100;
                        int close = random.nextInt(10);

                        if (close < 7)
                            ctx.close();

                        scheduledFuture = ctx.executor().schedule(runConnect, waitForConnect, TimeUnit.MILLISECONDS);
//                        trySendCount = random.nextInt(10);
                        trySendCount = 10;
                        tryConnectCount--;
                    }
                }
                currentLen = 0;
            }
            if ((buf.readableBytes() < currentLen) || (currentLen == 0 && buf.readableBytes() < 1))
                break;
        }
    }

    Runnable runConnect = new Runnable() {
        public void run() {
            connect();
        }
    };
    Runnable runSend = new Runnable() {
        public void run() {
            send();
        }
    };

    public static byte[] DecToBCDArray(long num, int byteLen) {
        int digits = 0;

        long temp = num;
        while (temp != 0) {
            digits++;
            temp /= 10;
        }

        if (byteLen == -1)
            byteLen = digits % 2 == 0 ? digits / 2 : (digits + 1) / 2;
        boolean isOdd = digits % 2 != 0;

        byte bcd[] = new byte[byteLen];

        for (int i = 0; i < digits; i++) {
            byte tmp = (byte) (num % 10);

            if (i == digits - 1 && isOdd)
                bcd[i / 2] = tmp;
            else if (i % 2 == 0)
                bcd[i / 2] = tmp;
            else {
                byte foo = (byte) (tmp << 4);
                bcd[i / 2] |= foo;
            }

            num /= 10;
        }

        for (int i = 0; i < byteLen / 2; i++) {
            byte tmp = bcd[i];
            bcd[i] = bcd[byteLen - i - 1];
            bcd[byteLen - i - 1] = tmp;
        }

        return bcd;
    }

    public static String BCDtoString(byte bcd) {
        StringBuffer sb = new StringBuffer();
        byte high = (byte) (bcd & 0xf0);
        high >>>= (byte) 4;
        high = (byte) (high & 0x0f);
        byte low = (byte) (bcd & 0x0f);
        sb.append(high);
        sb.append(low);
        return sb.toString();
    }


    public static String BCDtoString(byte[] bcd) {

        StringBuffer sb = new StringBuffer();

        for (int i = 0; i < bcd.length; i++) {
            sb.append(BCDtoString(bcd[i]));
        }

        return sb.toString();
    }
}
