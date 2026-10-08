package com.example.cardSearch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"cards.import.enabled=false", "cards.database-path=build/test-context/cards.db"})
class CardSearchApplicationTests {

	@Test
	void contextLoads() {
	}

}
