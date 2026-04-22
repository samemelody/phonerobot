package com.phonerobot.app.ui

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.phonerobot.app.R
import com.phonerobot.app.robot.JsScriptManager

/**
 * Activity for editing JavaScript scripts
 */
class ScriptEditorActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ScriptEditorActivity"
    }

    private lateinit var scriptManager: JsScriptManager
    private lateinit var editText: EditText
    private var scriptName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_script_editor)

        scriptManager = JsScriptManager(this)
        scriptName = intent.getStringExtra("script_name")
        
        setupViews()
        loadScript()
    }

    private fun setupViews() {
        editText = findViewById(R.id.script_edit_text)
        
        val saveButton: Button = findViewById(R.id.btn_save)
        saveButton.setOnClickListener {
            saveScript()
        }
        
        val executeButton: Button = findViewById(R.id.btn_execute)
        executeButton.setOnClickListener {
            executeScript()
        }
        
        val cancelButton: Button = findViewById(R.id.btn_cancel)
        cancelButton.setOnClickListener {
            finish()
        }
    }

    private fun loadScript() {
        scriptName?.let { name ->
            val content = scriptManager.loadScript(name)
            if (content != null) {
                editText.setText(content)
            } else {
                Toast.makeText(this, "Failed to load script", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            // New script
            editText.setText("""
// New script
var protocol = globalProtocol;
// Use protocol.packForwardRequest(speed, time) for forward movement
// Example: return protocol.packForwardRequest(50, 1000);
            """.trimIndent())
        }
    }

    private fun saveScript() {
        val content = editText.text.toString()
        return if (content.isNotBlank()) {
            val name = scriptName ?: "manual_${System.currentTimeMillis()}"
            scriptManager.saveScript(content, name)
            Toast.makeText(this, "Script saved", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "Script content cannot be empty", Toast.LENGTH_SHORT).show()
        }
    }

    private fun executeScript() {
        // This will be implemented when we enhance the JS sandbox
        Toast.makeText(this, "Script execution will be available after sandbox enhancement", Toast.LENGTH_SHORT).show()
    }
}