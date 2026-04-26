package com.phonerobot.app.ai

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * General utility tools class providing various helper functions
 */
class GeneralTools : ToolSet {

    @Tool(description = "Get the current date and time. Call this tool when user asks about current time, date, or what day it is today. Returns formatted date and time string.")
    fun getCurrentTime(): String {
        val current = LocalDateTime.now()
        
        // Format date and time
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val weekFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)
        
        val date = current.format(dateFormatter)
        val time = current.format(timeFormatter)
        val weekDay = current.format(weekFormatter)
        
        return "Current date and time: $date $weekDay $time"
    }

    @Tool(description = "Get the current date only. Call this tool when user asks only about date or what date it is today. Returns formatted date string.")
    fun getCurrentDate(): String {
        val current = LocalDateTime.now()
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val weekFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)
        
        val date = current.format(dateFormatter)
        val weekDay = current.format(weekFormatter)
        
        return "Today is: $date $weekDay"
    }
}
