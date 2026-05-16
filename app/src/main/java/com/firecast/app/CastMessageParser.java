package com.firecast.app;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Encode/décode les messages du protocole Cast V2 (format Protobuf simplifié).
 * Structure : 4 octets longueur (big-endian) + message protobuf
 */
public class CastMessageParser {

    public static class CastMessage {
        public String sourceId;
        public String destinationId;
        public String namespace;
        public String payload;
    }

    // Encode un message Cast V2 en bytes
    public static byte[] encode(String sourceId, String destinationId,
                                String namespace, String payload) throws Exception {
        ByteArrayOutputStream proto = new ByteArrayOutputStream();

        // Field 1: protocol_version = 0
        proto.write(new byte[]{0x08, 0x00});

        // Field 2: source_id
        writeString(proto, 2, sourceId);

        // Field 3: destination_id
        writeString(proto, 3, destinationId);

        // Field 4: namespace
        writeString(proto, 4, namespace);

        // Field 5: payload_type = 0 (STRING)
        proto.write(new byte[]{0x28, 0x00});

        // Field 6: payload_utf8
        writeString(proto, 6, payload);

        byte[] protoBytes = proto.toByteArray();

        // Préfixe 4 octets = longueur du message
        ByteBuffer buf = ByteBuffer.allocate(4 + protoBytes.length);
        buf.putInt(protoBytes.length);
        buf.put(protoBytes);
        return buf.array();
    }

    // Décode un message Cast V2 depuis des bytes
    public static CastMessage decode(byte[] data) {
        CastMessage msg = new CastMessage();
        int i = 0;
        while (i < data.length) {
            int tag = data[i] >> 3;
            int type = data[i] & 0x07;
            i++;

            if (type == 2) { // length-delimited
                int len = readVarint(data, i);
                i += varintSize(data, i);
                String value = new String(data, i, len, StandardCharsets.UTF_8);
                i += len;

                if (tag == 2) msg.sourceId = value;
                else if (tag == 3) msg.destinationId = value;
                else if (tag == 4) msg.namespace = value;
                else if (tag == 6) msg.payload = value;
            } else {
                // Sauter les autres types
                i++;
            }
        }
        return msg;
    }

    private static void writeString(ByteArrayOutputStream out, int field, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write((field << 3) | 2); // tag
        out.write(bytes.length);     // longueur
        out.write(bytes);            // contenu
    }

    private static int readVarint(byte[] data, int offset) {
        int result = 0, shift = 0;
        while (offset < data.length) {
            byte b = data[offset++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    private static int varintSize(byte[] data, int offset) {
        int size = 0;
        while (offset < data.length && (data[offset++] & 0x80) != 0) size++;
        return size + 1;
    }
}
