package com.example.carwithin;

import android.os.Build;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;

/** PXC Android TCP session for the supported Tailing and Zeeho displays. */
final class TailingConnection implements AutoCloseable {
    private final P2pController.Route route;
    private final MirrorSession.Listener listener;
    private final StreamProfile profile;
    private final List<Closeable> resources=new CopyOnWriteArrayList<>();
    private final ExecutorService threads=Executors.newCachedThreadPool();
    private final CountDownLatch identified=new CountDownLatch(1),videoArrived=new CountDownLatch(1),mediaStarted=new CountDownLatch(1);
    private String phoneId;
    private boolean videoPrefixSent;
    private volatile Socket reverseControl;
    private boolean zeeho(){return profile.transport()==StreamProfile.Transport.ZEEHO_TCP;}
    private String brand(){return zeeho()?"极核":"台铃";}
    private volatile boolean closed;
    private volatile Socket video;
    private volatile IOException failure;
    private final android.content.Context context;
    private KeyPair keys;
    private volatile String instrumentIdentity;
    private volatile byte[] signedIdentity;
    TailingConnection(android.content.Context context,P2pController.Route route,StreamProfile profile,MirrorSession.Listener listener){this.context=context;this.route=route;this.profile=profile;this.listener=listener;}
    private <T extends Closeable> T track(T c)throws IOException {
        resources.add(c);if(closed){c.close();throw new IOException("台铃会话已停止");}return c;
    }
    void start()throws Exception {
        loadIdentity();
        // All listeners must exist before announcing the phone to the instrument.
        for(int port:new int[]{10922,10921,10920}) {
            ServerSocket server=track(new ServerSocket());server.setReuseAddress(true);
            try{server.bind(new InetSocketAddress(route.local(),port));}
            catch(BindException e){throw new IOException(brand()+" TCP "+port+"被占用，请退出官方App投屏后重试",e);}
            threads.execute(()->accept(server,port));
        }
        listener.stats(brand()+" Wi-Fi 已连接 · 正在协商投屏会话");
        try(Socket s=outbound(10930)) {
            s.getOutputStream().write(TailingProtocol.message(0x70000010,new JSONObject().put("phoneType","Android").put("packageName",context.getPackageName()).toString()));
            TailingProtocol.Message reply=TailingProtocol.read(s.getInputStream(),zeeho());
            if(reply.command()!=0x70000011||!json(reply).optBoolean("status"))throw new IOException("仪表未接受投屏连接请求");
        }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(!closed && failure==null && (identified.getCount()!=0||videoArrived.getCount()!=0||mediaStarted.getCount()!=0)) {
            if(System.nanoTime()>=deadline)throw new SocketTimeoutException(brand()+"会话未就绪，请退出官方投屏后重试；必要时重新连接仪表");
            Thread.sleep(50);
        }
        check();listener.log(brand()+"投屏会话已就绪：TCP10920，等待逐帧反馈");
    }
    private void loadIdentity()throws Exception {
        synchronized(TailingConnection.class) {
            android.content.SharedPreferences prefs=context.getSharedPreferences("tailing_identity", android.content.Context.MODE_PRIVATE);
            String pub=prefs.getString("public",null),priv=prefs.getString("private",null);
            if(pub!=null&&priv!=null) {
                KeyFactory f=KeyFactory.getInstance("RSA");
                keys=new KeyPair(f.generatePublic(new X509EncodedKeySpec(Base64.decode(pub,Base64.NO_WRAP))),f.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv,Base64.NO_WRAP))));
            } else {
                KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(1024);keys=g.generateKeyPair();
            }
            phoneId=prefs.getString("phone_id",null);if(phoneId==null)phoneId=UUID.randomUUID().toString();
            if(!prefs.edit().putString("public",Base64.encodeToString(keys.getPublic().getEncoded(),Base64.NO_WRAP))
                    .putString("private",Base64.encodeToString(keys.getPrivate().getEncoded(),Base64.NO_WRAP)).putString("phone_id",phoneId).commit())
                throw new IOException("无法保存台铃本机身份");
        }
    }
    private Socket outbound(int port)throws IOException {
        Socket s=track(new Socket());if(route.network()!=null)route.network().bindSocket(s);
        s.bind(new InetSocketAddress(route.local(),0));s.connect(new InetSocketAddress(route.remote(),port),3000);s.setSoTimeout(3000);return s;
    }
    private void accept(ServerSocket server,int port) {
        try {
            while(!closed) {
                Socket s=track(server.accept());
                if(!route.remote().equals(s.getInetAddress())){s.close();continue;}
                s.setTcpNoDelay(true);s.setSoTimeout(10000);
                if(port==10920){if(video!=null){s.close();continue;}video=s;videoArrived.countDown();}
                else threads.execute(()->handle(s,port));
            }
        }catch(Exception e){fail(e);}
    }
    private void handle(Socket s,int port) {
        try {
            if(port==10921) { auxiliary(s);return; }
            InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();
            while(!closed) {
                TailingProtocol.Message m=TailingProtocol.read(in,zeeho());int cmd=m.command();String response=null;boolean reply=true;
                switch(cmd) {
                    case 0x10000:case 0x20000:case 0x10740:case 0x10040:case 0x70000000:break;
                    case 0x10690:case 0x10780:case 0x103e0:
                        if(!zeeho())throw new IOException("意外的 PXC 能力消息");
                        break;
                    case 0x0:case 0x20591:case 0x70000001:reply=false;break;
                    case 0x10010:
                        JSONObject info=json(m);
                        String name=info.optString("HUName");
                        if(zeeho() ? !route.target().name().equals(name)
                                || !DeviceCatalog.zeehoCandidate(name) || !info.optBoolean("supportScreenMirroring")
                                : !"TL-DASH-V2".equals(name))
                            throw new IOException("仪表身份或投屏能力与所选配置不一致");
                        String huid=info.optString("HUID");if(huid.isEmpty())throw new IOException("仪表未提供会话身份");
                        instrumentIdentity=huid;response=phoneInfo(huid);identified.countDown();listener.log(brand()+"能力已核对："+name);break;
                    case 0x10450:
                        long time=System.currentTimeMillis();response=new JSONObject().put("time",time).put("currentTime",time+TimeZone.getDefault().getOffset(time)).put("currentTimeZone",TimeZone.getDefault().getID()).toString();break;
                    case 0x80000010:reply=false;break;
                    case 0x20021:case 0x20031:reply=false;break; // Responses, never requests.
                    default:throw new IOException("未支持的 "+brand()+" 协商命令 0x"+Integer.toHexString(cmd));
                }
                if(reply)sendControl(s,cmd+1,response);
                if(cmd==0x20000 && zeeho()) {
                    reverseControl=s;
                    sendControl(s,0x20590,"{\"control\":1}");
                    threads.execute(()->zeehoKeepalive(s));
                } else if(cmd==0x20000) {
                    sendControl(s,0x20490,"{\"status\":0}");
                    sendControl(s,0x20020,display(2));
                    // Full-frame mirroring uses SAME mode while the host's native navigation UI is backgrounded.
                    sendControl(s,0x20030,display());
                    sendControl(s,0x20050,null);
                    sendControl(s,0x205a0,"{\"isMaskVisible\":false}");
                    // Reverse control receives replies, not the instrument's primary-channel heartbeats.
                    // Keep it active as in the successful capture, even for a static image source.
                    threads.execute(()->{
                        try{while(!closed&&!s.isClosed()){Thread.sleep(2000);sendControl(s,0x20030,display());}}
                        catch(Exception e){fail(e);}
                    });
                }
            }
        }catch(Exception e){fail(e);}
    }
    private void zeehoKeepalive(Socket s) {
        try {
            while(!closed && !s.isClosed()) {
                Thread.sleep(1000);
                if(!closed)sendControl(s,0x70000000,null);
            }
        } catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(IOException e){fail(e);}
    }
    private void sendControl(Socket s,int command,String json)throws IOException {
        synchronized(s){s.getOutputStream().write(TailingProtocol.message(command,json));s.getOutputStream().flush();}
    }
    private String display()throws Exception {return display(1);}
    private String display(int mode)throws Exception {
        android.view.WindowManager windows = context.getSystemService(android.view.WindowManager.class);
        android.view.Display display = windows.getDefaultDisplay();
        android.graphics.Point size = new android.graphics.Point();
        display.getRealSize(size);
        int[] phone = {size.x, size.y, display.getRotation()};
        int width=mode==2?Math.min(phone[0],phone[1]):phone[0],height=mode==2?Math.max(phone[0],phone[1]):phone[1];
        return new JSONObject().put("displayRotation",mode==2?0:phone[2]).put("width",width).put("height",height)
                .put("enableAccessibility",false).put("enableAOAHid",false).put("mode",mode).toString();
    }
    private static JSONObject json(TailingProtocol.Message m)throws Exception{return new JSONObject(new String(m.payload(),StandardCharsets.UTF_8));}
    private String phoneInfo(String huid)throws Exception {
        Cipher rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding");rsa.init(Cipher.ENCRYPT_MODE,keys.getPrivate());
        byte[] identity=huid.getBytes(StandardCharsets.UTF_8);if(identity.length>117)throw new IOException("仪表身份长度异常");
        signedIdentity=rsa.doFinal(identity);
        JSONObject j=new JSONObject().put("pxcVersion","1.0.2").put("phoneUUID",phoneId).put("phoneBrand",Build.BRAND).put("phoneModel",Build.MODEL)
            .put("phoneOsVersion",Integer.toString(Build.VERSION.SDK_INT)).put("phoneOs","Android").put("package",context.getPackageName())
            .put("versionCode",BuildConfig.VERSION_CODE).put("token",0).put("pubkey",Base64.encodeToString(keys.getPublic().getEncoded(),Base64.NO_WRAP))
            .put("encryptedHUID",Base64.encodeToString(signedIdentity,Base64.NO_WRAP)).put("bluetoothName","").put("supportH264IFrame",zeeho());
        if(zeeho())j.put("supportFunctionEx",1073741952);else j.put("supportFunction",128);
        return j.toString();
    }
    private void auxiliary(Socket s)throws Exception {
        InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();
        boolean configured=false;s.setSoTimeout(0);
        while(!closed) {
            TailingProtocol.Auxiliary request=TailingProtocol.readAuxiliary(in);
            int command=request.command(),token=request.token();byte[] payload=request.payload();
            switch(command) {
                case 16:
                    if(payload.length<32)throw new IOException("台铃媒体配置过短");
                    int dimensions=ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    int expected=zeeho()?(488<<16|1024):(272<<16|480);
                    if(dimensions!=expected)throw new IOException("仪表媒体参数与配置不符："+(dimensions&65535)+"×"+(dimensions>>>16));
                    if(!identified.await(5,TimeUnit.SECONDS))throw new IOException("媒体配置早于有效仪表身份");
                    byte[] echoed=Base64.decode(Arrays.copyOfRange(payload,32,payload.length),Base64.DEFAULT);
                    if(!Arrays.equals(echoed,signedIdentity))throw new IOException("台铃媒体身份回显不匹配");
                    Cipher rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding");rsa.init(Cipher.DECRYPT_MODE,keys.getPublic());
                    if(!instrumentIdentity.equals(new String(rsa.doFinal(echoed),StandardCharsets.UTF_8)))throw new IOException("台铃媒体身份校验失败");
                    byte[] response=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(zeeho()?2:1).putShort((short)profile.width()).putShort((short)profile.height()).array();
                    out.write(TailingProtocol.auxiliary(17,token,response));out.flush();configured=true;
                    listener.log("台铃媒体配置已回应："+payload.length+"字节，身份回显核对通过");break;
                case 112:
                    if(!configured)throw new IOException("仪表在媒体配置前请求启动");
                    out.write(TailingProtocol.auxiliary(113,token,null));out.flush();mediaStarted.countDown();
                    listener.log("台铃媒体启动请求0x70已回应0x71");break;
                case 64:
                    out.write(TailingProtocol.auxiliary(65,token,null));out.flush();break;
                case 48:
                    out.write(TailingProtocol.auxiliary(49,token,ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(5002).putInt(1).array()));out.flush();break;
                case 96:
                    // Only the captured single-frame pull mode is implemented; no movie credits.
                    out.write(TailingProtocol.auxiliary(97,token,"{\"supportFunction\":0}".getBytes(StandardCharsets.UTF_8)));out.flush();break;
                case 32:break; // Do not execute screen touch commands.
                default:
                    out.write(TailingProtocol.auxiliary(32514,token,null));out.flush();
                    listener.log("台铃配套通道暂不支持0x"+Integer.toHexString(command));break;
            }
        }
    }
    void send(byte[] jpeg)throws IOException {
        check();Socket s=video;if(s==null)throw new IOException("台铃视频通道未连接");
        s.setSoTimeout(5000);
        TailingProtocol.Auxiliary request=TailingProtocol.readAuxiliary(s.getInputStream());
        if(request.command()!=114||request.payload().length!=0)throw new IOException("台铃逐帧请求格式不匹配");
        TailingProtocol.writeJpeg(s.getOutputStream(),jpeg);
    }
    void sendAvc(byte[] annexB)throws IOException {
        check();
        Socket s=video;
        if(s==null)throw new IOException("极核视频通道未连接");
        s.setSoTimeout(5000);
        if(!TailingProtocol.feedback(TailingProtocol.exact(s.getInputStream(),8)))
            throw new IOException("极核逐帧请求格式不匹配");
        OutputStream out=s.getOutputStream();
        if(!videoPrefixSent){out.write(new byte[8]);videoPrefixSent=true;}
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(annexB.length).array());
        out.write(annexB);
        out.flush();
    }
    void cancelVideoRead() {
        Socket socket=video;
        // Wake a blocked frame request. The session worker still owns the control channel.
        if(socket!=null)try{socket.shutdownInput();}
        catch(IOException ignored){}
    }
    void finishMedia() {
        Socket control=reverseControl;
        if(!closed && control!=null)try{sendControl(control,0x20590,"{\"control\":0}");}
        catch(IOException ignored){}
    }
    private void check()throws IOException {if(failure!=null)throw failure;if(closed)throw new IOException("台铃会话已停止");}
    private void fail(Exception e){if(!closed){failure=e instanceof IOException?(IOException)e:new IOException(e);close();}}
    @Override public void close(){closed=true;for(Closeable c:resources)try{c.close();}catch(IOException ignored){}threads.shutdownNow();}
}
