package com.k2iot.turncred.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HashUtilTest {

    @Test
    void sha256HexProducesCorrectHash() {
        String input = "test-api-key";
        String hash = HashUtil.sha256Hex(input);
        assertThat(hash).isEqualTo("4c806362b613f7496abf284146efd31da90e4b16169fe001841ca17290f427c4");
    }

    @Test
    void sha256HexHandlesNull() {
        assertThat(HashUtil.sha256Hex(null)).isNull();
    }
}
