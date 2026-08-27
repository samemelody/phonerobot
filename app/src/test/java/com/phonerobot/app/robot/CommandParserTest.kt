package com.phonerobot.app.robot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for CommandParser — parses AI-generated text into RobotCommand.
 * Pure JVM test; android.util.Log is stubbed via unitTests.isReturnDefaultValues.
 */
class CommandParserTest {

    @Test
    fun `parses MOVE_FWD with distance`() {
        val cmd = CommandParser.parse("MOVE_FWD 100")
        assertEquals(RobotCommand.MoveForward(100), cmd)
    }

    @Test
    fun `parses MOVE_BACK with distance`() {
        assertEquals(RobotCommand.MoveBackward(50), CommandParser.parse("MOVE_BACK 50"))
    }

    @Test
    fun `parses TURN_LEFT and TURN_RIGHT with degrees`() {
        assertEquals(RobotCommand.TurnLeft(90), CommandParser.parse("TURN_LEFT 90"))
        assertEquals(RobotCommand.TurnRight(45), CommandParser.parse("TURN_RIGHT 45"))
    }

    @Test
    fun `parses STOP case-insensitively`() {
        assertEquals(RobotCommand.Stop, CommandParser.parse("STOP"))
        assertEquals(RobotCommand.Stop, CommandParser.parse("stop"))
    }

    @Test
    fun `parses command embedded in longer AI output`() {
        val aiOutput = "Sure! Executing now.\nMOVE_FWD 30\nLet me know if you want to stop."
        assertEquals(RobotCommand.MoveForward(30), CommandParser.parse(aiOutput))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(RobotCommand.TurnLeft(15), CommandParser.parse("  TURN_LEFT 15  "))
    }

    @Test
    fun `returns Unknown for unparseable text`() {
        val cmd = CommandParser.parse("hello there robot")
        assertEquals(RobotCommand.Unknown("hello there robot"), cmd)
    }

    @Test
    fun `first matching pattern wins when multiple present`() {
        assertEquals(RobotCommand.MoveForward(10), CommandParser.parse("MOVE_FWD 10 MOVE_BACK 20"))
    }
}
