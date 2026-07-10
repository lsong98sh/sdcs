package com.qkinfotech.bizwax.sdcs;

import com.qkinfotech.bizwax.sdcs.command.CommandDispatcher;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GeoTest {

    private DatabaseManager dbManager;
    private CommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dbManager = new DatabaseManager();
        dispatcher = new CommandDispatcher(dbManager);
    }

    private static RedisMessage bulkString(String s) {
        return RedisMessage.bulkString(s.getBytes());
    }

    private static List<RedisMessage> args(String... strings) {
        return List.of(strings).stream().map(s -> RedisMessage.bulkString(s.getBytes())).toList();
    }

    @Test
    void testGeoAdd() {
        RedisMessage r = dispatcher.dispatch("GEOADD", args("geo1", "13.361389", "38.115556", "Palermo"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(1, r.getIntegerValue());
    }

    @Test
    void testGeoAddMultiple() {
        RedisMessage r = dispatcher.dispatch("GEOADD", args("geo2", "13.361389", "38.115556", "Palermo",
                "15.087269", "37.502669", "Catania"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(2, r.getIntegerValue());
    }

    @Test
    void testGeoDist() {
        dispatcher.dispatch("GEOADD", args("geo3", "13.361389", "38.115556", "Palermo",
                "15.087269", "37.502669", "Catania"));

        RedisMessage r = dispatcher.dispatch("GEODIST", args("geo3", "Palermo", "Catania"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertNotNull(r.asString());
        double distance = Double.parseDouble(r.asString());
        assertTrue(distance > 0);
    }

    @Test
    void testGeoDistNonExistentMember() {
        dispatcher.dispatch("GEOADD", args("geo4", "13.361389", "38.115556", "Palermo"));

        RedisMessage r = dispatcher.dispatch("GEODIST", args("geo4", "Palermo", "NonExistent"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertNull(r.asString());
    }

    @Test
    void testGeoDistKm() {
        dispatcher.dispatch("GEOADD", args("geo5", "13.361389", "38.115556", "Palermo",
                "15.087269", "37.502669", "Catania"));

        RedisMessage r = dispatcher.dispatch("GEODIST", args("geo5", "Palermo", "Catania", "km"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertNotNull(r.asString());
    }

    @Test
    void testGeoPos() {
        dispatcher.dispatch("GEOADD", args("geo6", "13.361389", "38.115556", "Palermo",
                "15.087269", "37.502669", "Catania"));

        RedisMessage r = dispatcher.dispatch("GEOPOS", args("geo6", "Palermo", "Catania"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertEquals(2, elements.size());
        assertNotNull(elements.get(0));
        assertEquals(RedisMessage.Type.ARRAY, elements.get(0).getType());
        List<RedisMessage> pos = elements.get(0).getElements();
        assertEquals(2, pos.size());
        assertNotNull(pos.get(0).asString());
        assertNotNull(pos.get(1).asString());
    }

    @Test
    void testGeoPosNonExistentMember() {
        dispatcher.dispatch("GEOADD", args("geo7", "13.361389", "38.115556", "Palermo"));

        RedisMessage r = dispatcher.dispatch("GEOPOS", args("geo7", "Palermo", "NonExistent"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertEquals(2, elements.size());
        assertEquals(RedisMessage.Type.ARRAY, elements.get(0).getType());
        assertTrue(elements.get(1).isNullBulkString());
    }

    @Test
    void testGeoHash() {
        dispatcher.dispatch("GEOADD", args("geo8", "13.361389", "38.115556", "Palermo",
                "15.087269", "37.502669", "Catania"));

        RedisMessage r = dispatcher.dispatch("GEOHASH", args("geo8", "Palermo", "Catania"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertEquals(2, elements.size());
        assertNotNull(elements.get(0).asString());
        assertNotNull(elements.get(1).asString());
    }

    @Test
    void testGeoHashNonExistentMember() {
        dispatcher.dispatch("GEOADD", args("geo9", "13.361389", "38.115556", "Palermo"));

        RedisMessage r = dispatcher.dispatch("GEOHASH", args("geo9", "Palermo", "NonExistent"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertEquals(2, elements.size());
        assertNotNull(elements.get(0).asString());
        assertTrue(elements.get(1).isNullBulkString());
    }

    @Test
    void testGeoWrongType() {
        dispatcher.dispatch("SET", args("notgeo", "value"));

        RedisMessage r = dispatcher.dispatch("GEOADD", args("notgeo", "13.361389", "38.115556", "member"));
        assertEquals(RedisMessage.Type.ERROR, r.getType());
    }
}
