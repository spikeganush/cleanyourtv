package com.gathin.tvhome;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Runs one shell command as the adb shell user by talking to the TV's own adbd on 127.0.0.1:5555.
 * Some things only the shell user can do, such as reading raw remote events from /dev/input.
 * The first connection shows "Allow USB debugging?" on screen for this app's key; tick
 * "Always allow". Needs network debugging on port 5555 (USB debugging on, on most TVs).
 */
final class LocalShell {
    private static final String TAG = "LocalShell";
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    // DER prefix of a SHA-1 DigestInfo: adbd checks the token as if it were a SHA-1 hash.
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private LocalShell() {}

    /**
     * Runs {@code command} and returns its output, or null when adbd could not be reached in
     * {@code attempts} tries five seconds apart (it starts late after boot). Call off the main thread.
     */
    static String run(Context context, String command, int attempts) {
        for (int i = 0; i < attempts; i++) {
            try {
                String out = exec(keys(context), command);
                Log.i(TAG, "ran command: " + out.trim());
                return out;
            } catch (Exception e) {
                Log.w(TAG, "attempt " + (i + 1) + " failed: " + e);
                if (i + 1 < attempts) {
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { return null; }
                }
            }
        }
        return null;
    }

    private static String exec(KeyPair keys, String command) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            send(out, CNXN, 0x01000000, 256 * 1024, "host::\0".getBytes(StandardCharsets.UTF_8));

            boolean sentKey = false, signed = false;
            socket.setSoTimeout(10000);
            while (true) {
                Msg m = read(in);
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected " + m.cmd);
                if (!signed) {
                    send(out, AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                    signed = true;
                } else if (!sentKey) {
                    // Key not trusted yet: adbd shows the prompt on screen. Give the user a minute.
                    send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKey((RSAPublicKey) keys.getPublic()));
                    sentKey = true;
                    socket.setSoTimeout(60000);
                } else {
                    throw new IOException("key not accepted");
                }
            }

            socket.setSoTimeout(30000);
            send(out, OPEN, 1, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            while (true) {
                Msg m = read(in);
                if (m.cmd == WRTE) {
                    output.append(new String(m.data, StandardCharsets.UTF_8));
                    send(out, OKAY, 1, m.arg0, new byte[0]);
                } else if (m.cmd == CLSE) {
                    send(out, CLSE, 1, m.arg0, new byte[0]);
                    return output.toString();
                }
            }
        }
    }

    private static final class Msg { int cmd, arg0; byte[] data; }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        b.getInt();
        m.data = new byte[b.getInt()];
        in.readFully(m.data);
        return m;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte d : data) sum += d & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.update(SHA1_PREFIX);
        return cipher.doFinal(token);
    }

    /** The public key in adb's own format: Android's RSAPublicKey struct, base64, then a name. */
    private static byte[] publicKey(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        int words = 2048 / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        long n0inv = r32.subtract(n.mod(r32).modInverse(r32)).longValue();
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 8 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt((int) n0inv);
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).intValue());
        b.putInt(key.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tvhome@localhost\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** One key pair per install, kept in the app's private files so "Always allow" sticks. */
    private static KeyPair keys(Context context) throws Exception {
        File priv = new File(context.getFilesDir(), "adbkey"), pub = new File(context.getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        try (FileOutputStream o = new FileOutputStream(priv)) { o.write(kp.getPrivate().getEncoded()); }
        try (FileOutputStream o = new FileOutputStream(pub)) { o.write(kp.getPublic().getEncoded()); }
        return kp;
    }
}
