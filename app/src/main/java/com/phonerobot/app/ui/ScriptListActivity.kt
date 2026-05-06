package com.phonerobot.app.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.phonerobot.app.R
import com.phonerobot.app.robot.JsScriptManager

/**
 * Activity to display list of saved JavaScript scripts
 * Allows users to view, edit, delete, and create new scripts
 */
class ScriptListActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ScriptListActivity"
    }

    private lateinit var scriptManager: JsScriptManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ScriptAdapter
    private val scriptList = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_script_list)

        scriptManager = JsScriptManager(this)
        scriptManager.initializeStorage()

        setupViews()
        loadScripts()
    }

    private fun setupViews() {
        recyclerView = findViewById(R.id.script_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ScriptAdapter(scriptList) { scriptName ->
            // Handle script selection
            openScriptEditor(scriptName)
        }
        recyclerView.adapter = adapter

        val newScriptButton: Button = findViewById(R.id.btn_new_script)
        newScriptButton.setOnClickListener {
            createNewScript()
        }

        val refreshButton: Button = findViewById(R.id.btn_refresh)
        refreshButton.setOnClickListener {
            loadScripts()
        }
    }

    private fun loadScripts() {
        scriptList.clear()
        scriptList.addAll(scriptManager.listScripts())
        adapter.notifyDataSetChanged()
    }

    private fun createNewScript() {
        // Generate basic script template
        val template = """
// Generated script
var protocol = globalProtocol;
// Example: Move forward
return protocol.packForwardRequest(50, 1000);
        """.trimIndent()
        
        // Generate timestamp-based filename (human readable)
        val dateFormat = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
        val timestamp = dateFormat.format(java.util.Date())
        val fileName = "script_$timestamp"
        scriptManager.saveScript(template, fileName)
        loadScripts()
    }

    private fun openScriptEditor(scriptName: String) {
        val intent = Intent(this, ScriptEditorActivity::class.java)
        intent.putExtra("script_name", scriptName)
        startActivity(intent)
    }

    
    /**
     * Adapter for script list RecyclerView
     */
    inner class ScriptAdapter(
        private val scripts: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<ScriptAdapter.ViewHolder>() {

        inner class ViewHolder(val button: Button) : RecyclerView.ViewHolder(button)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val button = Button(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            return ViewHolder(button)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val scriptName = scripts[position]
            holder.button.text = scriptName
            holder.button.setOnClickListener { onClick(scriptName) }
        }

        override fun getItemCount() = scripts.size
    }
}