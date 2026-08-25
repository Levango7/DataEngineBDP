package com.levango7.dataenginebdp.sqlgateway.calcite.join;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.Arrays;

@DisplayName("SpillSerDe 序列化/反序列化")
class SpillSerDeTest {

    @Test
    @DisplayName("round-trip: 全部10种类型")
    void roundTripAllTypes() throws IOException {
        Object[] values = {
            null,                    // 0 null
            42,                      // 1 int
            123L,                    // 2 long
            3.14,                    // 3 double
            2.5f,                    // 4 float
            "hello",                 // 5 String
            true,                    // 6 boolean
            (short) 7,              // 7 short
            (byte) 8,               // 8 byte
            new byte[]{1, 2, 3},    // 9 byte[]
            "fallback"              // 未知类型走 toString → String
        };
        Row original = new Row(values);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        SpillSerDe.writeRow(dos, original);
        dos.flush();

        DataInputStream dis = new DataInputStream(
            new ByteArrayInputStream(baos.toByteArray()));
        Row restored = SpillSerDe.readRow(dis);

        // 逐列比较（byte[] 需特殊处理）
        assertEquals(original.size(), restored.size());
        for (int i = 0; i < original.size(); i++) {
            Object exp = original.get(i);
            Object act = restored.get(i);
            if (exp instanceof byte[] expBytes && act instanceof byte[] actBytes) {
                assertArrayEquals(expBytes, actBytes);
            } else {
                assertEquals(exp, act);
            }
        }
    }

    @Test
    @DisplayName("readValueByType: 每种类型独立验证")
    void readValueByTypeEachType() throws IOException {
        // null
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeByte(0);
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
            assertNull(SpillSerDe.readValueByType(dis, 0));
        }
        // int
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(99);
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
            assertEquals(99, SpillSerDe.readValueByType(dis, 1));
        }
        // long
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeLong(999L);
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
            assertEquals(999L, SpillSerDe.readValueByType(dis, 2));
        }
        // double
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeDouble(1.5);
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
            assertEquals(1.5, SpillSerDe.readValueByType(dis, 3));
        }
        // float
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeFloat(0.25f);
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
            assertEquals(0.25f, SpillSerDe.readValueByType(dis, 4));
        }
        // String
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeUTF("test");
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
            assertEquals("test", SpillSerDe.readValueByType(dis, 5));
        }
        // boolean
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeBoolean(true);
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
            assertEquals(true, SpillSerDe.readValueByType(dis, 6));
        }
        // short
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeShort((short) 10);
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
            assertEquals((short) 10, SpillSerDe.readValueByType(dis, 7));
        }
        // byte
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeByte((byte) 3);
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
            assertEquals((byte) 3, SpillSerDe.readValueByType(dis, 8));
        }
        // byte[]
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            byte[] data = {10, 20, 30};
            dos.writeInt(data.length);
            dos.write(data);
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
            assertArrayEquals(data, (byte[]) SpillSerDe.readValueByType(dis, 9));
        }
    }

    @Test
    @DisplayName("readValueByType: 未知类型抛 IOException")
    void readValueByTypeUnknownType() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertThrows(IOException.class, () -> SpillSerDe.readValueByType(dis, 99));
    }

    @Test
    @DisplayName("writeValue: 返回正确字节数")
    void writeValueReturnsCorrectByteCount() throws IOException {
        // null: 1 byte (type tag only)
        assertEquals(1, writeAndGetBytes(null).length);
        // int: 1 (type) + 4 (int) = 5
        assertEquals(5, writeAndGetBytes(42).length);
        // long: 1 + 8 = 9
        assertEquals(9, writeAndGetBytes(123L).length);
        // boolean: 1 + 1 = 2
        assertEquals(2, writeAndGetBytes(true).length);
    }

    private byte[] writeAndGetBytes(Object v) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        SpillSerDe.writeValue(dos, v);
        dos.flush();
        return baos.toByteArray();
    }

    @Test
    @DisplayName("多行 round-trip")
    void multipleRowsRoundTrip() throws IOException {
        Row row1 = new Row(new Object[]{1, "a", null});
        Row row2 = new Row(new Object[]{2L, "b", true});
        Row row3 = new Row(new Object[]{3.0, "c", new byte[]{1, 2}});

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        SpillSerDe.writeRow(dos, row1);
        SpillSerDe.writeRow(dos, row2);
        SpillSerDe.writeRow(dos, row3);
        dos.flush();

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertEquals(row1, SpillSerDe.readRow(dis));
        assertEquals(row2, SpillSerDe.readRow(dis));
        Row r3 = SpillSerDe.readRow(dis);
        assertEquals(row3.get(0), r3.get(0));
        assertEquals(row3.get(1), r3.get(1));
        assertArrayEquals((byte[]) row3.get(2), (byte[]) r3.get(2));
    }
}