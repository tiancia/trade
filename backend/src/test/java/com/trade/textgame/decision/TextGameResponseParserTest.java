package com.trade.textgame.decision;

import com.trade.textgame.model.TextGameScene;
import com.trade.textgame.model.TextGameTurnOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextGameResponseParserTest {
    private final TextGameResponseParser parser = new TextGameResponseParser();

    @Test
    void parsesStandardTurnJson() {
        TextGameTurnOutcome outcome = parser.parseTurn("""
                {
                  "result": "You earned cash but lost sleep.",
                  "statsDelta": {"money": 600, "health": -8, "skill": 2, "network": 0, "reputation": 1, "risk": 4},
                  "scene": {
                    "title": "Day 5",
                    "text": "The next pressure point arrives.",
                    "choices": [
                      {"id": "A", "label": "Take the shift", "hint": "cash"},
                      {"id": "B", "label": "Study at night", "hint": "skill"}
                    ]
                  }
                }
                """, false);

        assertEquals("You earned cash but lost sleep.", outcome.result());
        assertEquals(600, outcome.statsDelta().get("money"));
        assertEquals("Day 5", outcome.scene().title());
        assertEquals(2, outcome.scene().choices().size());
    }

    @Test
    void parsesOpeningFromMarkdownFence() {
        TextGameScene scene = parser.parseOpening("""
                ```json
                {
                  "scene": {
                    "title": "Day 0",
                    "text": "Rent is due tonight.",
                    "choices": [
                      {"id": "A", "label": "Call the landlord", "hint": "reputation"},
                      {"id": "B", "label": "Find a night job", "hint": "cash"},
                      {"id": "C", "label": "Ask a classmate", "hint": "network"}
                    ]
                  }
                }
                ```
                """);

        assertEquals("Day 0", scene.title());
        assertEquals("C", scene.choices().get(2).id());
    }

    @Test
    void missingSceneFailsForNonFinalTurn() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseTurn("""
                        {
                          "result": "No next scene.",
                          "statsDelta": {"money": 1}
                        }
                        """, false)
        );

        assertTrue(error.getMessage().contains("next scene"));
    }

    @Test
    void missingChoicesFailsForOpening() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseOpening("""
                        {"scene":{"title":"Day 0","text":"No choices."}}
                        """)
        );

        assertTrue(error.getMessage().contains("choices"));
    }

    @Test
    void missingEndingFailsForFinalTurn() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseTurn("""
                        {
                          "result": "Final action.",
                          "statsDelta": {"money": 1}
                        }
                        """, true)
        );

        assertTrue(error.getMessage().contains("ending"));
    }

    @Test
    void parsesFinalTurnEnding() {
        TextGameTurnOutcome outcome = parser.parseTurn("""
                {
                  "result": "You closed the last deal.",
                  "statsDelta": {"money": 2000, "health": -2, "skill": 4, "network": 3, "reputation": 5, "risk": -6},
                  "ending": {
                    "title": "Still Standing",
                    "grade": "B",
                    "summary": "The 100 days did not solve everything, but the direction changed.",
                    "echoes": ["The landlord call kept one door open.", "The night shifts left a health bill."]
                  }
                }
                """, true);

        assertEquals("Still Standing", outcome.ending().title());
        assertEquals("B", outcome.ending().grade());
        assertEquals(2, outcome.ending().echoes().size());
        assertNotNull(outcome.statsDelta());
    }
}
