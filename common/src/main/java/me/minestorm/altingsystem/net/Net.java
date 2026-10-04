package me.minestorm.altingsystem.net;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Wire format: [32 byte HMAC-SHA256][UTF type][byte argc][UTF arg]*
 *
 * The proxy only relays the raw bytes. Backends verify the HMAC with the
 * shared secret from config.yml, so a client can never forge a packet.
 *
 * Channel name is short + namespaced: valid on 1.8 (<=20 chars) and on 1.13+.
 *
 * Packet types:
 *   QUERY      targetName
 *   QUERY_RESP targetName, hSens, vSens, altCsv
 *   ALERT      suspect, hSens, vSens, altCsv
 */
public final class Net {
    public static final String CHANNEL = "msalt:main";

    public static final String QUERY      = "QUERY";
    public static final String QUERY_RESP = "QUERY_RESP";
    public static final String ALERT      = "ALERT";

    private Net() {}

    public static byte[] encode(String secret, String type, String... args) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(body);
            out.writeUTF(type);
            out.writeByte(args.length);
            for (String a : args) out.writeUTF(a == null ? "" : a);
            byte[] payload = body.toByteArray();

            ByteArrayOutputStream full = new ByteArrayOutputStream();
            full.write(mac(secret, payload));
            full.write(payload);
            return full.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return {type, args...} or null on invalid HMAC. */
    public static String[] decode(byte[] data, String secret) {
        if (data == null || data.length <= 32) return null;
        byte[] mac = Arrays.copyOfRange(data, 0, 32);
        byte[] body = Arrays.copyOfRange(data, 32, data.length);
        if (!MessageDigest.isEqual(mac, mac(secret, body))) return null;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
            String type = in.readUTF();
            int n = in.readUnsignedByte();
            String[] out = new String[n + 1];
            out[0] = type;
            for (int i = 0; i < n; i++) out[i + 1] = in.readUTF();
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] mac(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
