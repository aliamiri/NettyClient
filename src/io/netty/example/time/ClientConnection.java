package io.netty.example.time;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import org.apache.log4j.Logger;

import java.util.Random;

public class ClientConnection extends ChannelInboundHandlerAdapter {

    SocketChannel socketChannel;
    int clientId;
    Channel channel;
    String serverAddr;
    int serverPort;

    final static Logger logger = Logger.getLogger(ClientConnection.class);

    public void connect(EventLoopGroup loopGroup, String server, int port, int id)  {
        clientId = id;
        serverAddr = server;
        serverPort = port;
        Bootstrap b = new Bootstrap();
        b.group(loopGroup);

        b.channel(NioSocketChannel.class);
        b.option(ChannelOption.SO_KEEPALIVE, true);


        b.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(ClientConnection.this);
            }
        });

        try {
            ChannelFuture f = b.connect(server, port).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        logger.info("operationComplete : future result is successful");
                        socketChannel = (SocketChannel) future.channel();
                    } else {
                        logger.error("operationComplete : future result is not successful");
                    }
                }
            });
        }
        catch (Exception e){
            logger.error("connect for id " + clientId,e);
        }
    }

    public void send() {
        if (socketChannel != null && socketChannel.isActive()) {
            byte[] msg = generateMessage();
            ByteBuf buf = socketChannel.alloc().buffer(msg.length);
            buf.writeBytes(msg);
            socketChannel.writeAndFlush(buf);
        }
    }

    private byte[] generateMessage() {
        Random random = new Random();
        int rand = random.nextInt(100) + 900;
        byte[] msg = new byte[rand];
        new Random().nextBytes(msg);

        byte[] first60 = DecToBCDArray(60, 1);
        byte[] dest = "SO".getBytes();
        byte[] source = DecToBCDArray(clientId, 2);

        int length = msg.length + 2;
        byte[] len = DecToBCDArray(length, 2);

        byte[] completeMsg = new byte[length];

        for (int i = 0; i < completeMsg.length; ++i) {
            completeMsg[i] = i < 2 ? len[i] : (i < 3 ? first60[0] : (i < 5 ? dest[i - 3] : (i < 7 ? source[i - 5] : msg[i - 7])));
        }
        return completeMsg;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf in = (ByteBuf) msg;
        StringBuilder stringBuilder = new StringBuilder();

        byte[] len = new byte[2];
        try {
            len[0] = in.readByte();
            len[1] = in.readByte();
            int length = Integer.parseInt(BCDtoString(len));
            byte[] message = new byte[length - 7];
            in.readByte();


            byte[] source = new byte[2];

            source[0] = in.readByte();
            source[1] = in.readByte();

            in.readByte();
            in.readByte();

            int messageID = Integer.parseInt(BCDtoString(source));

            if (clientId != messageID) {
                logger.error("ClientId "+clientId+"is not equal messageId" + messageID);
            }
            else
                logger.info("ClientId " + clientId + "is equal messageId" + messageID);

        } catch (Exception ex) {
            logger.error("channelRead ",ex);
        } finally {
            ReferenceCountUtil.release(msg); // (2)
        }
    }


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
