package com.fawads.ai.ui.notes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fawads.ai.R
import com.fawads.ai.databinding.ActivityNotesBinding
import com.fawads.ai.util.Note
import com.fawads.ai.util.TaskManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotesBinding
    private val notes = mutableListOf<Note>()
    private lateinit var adapter: NoteAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notes.addAll(TaskManager.getNotes(this))
        adapter = NoteAdapter(notes) { note -> delete(note) }
        binding.notesRecycler.layoutManager = LinearLayoutManager(this)
        binding.notesRecycler.adapter = adapter

        binding.addNoteBtn.setOnClickListener { showAddDialog() }
    }

    private fun showAddDialog() {
        val input = AlertDialogInputView(this)
        AlertDialog.Builder(this)
            .setTitle("New Note")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val text = input.textValue()
                if (text.isNotBlank()) {
                    TaskManager.addNote(this, text)
                    notes.clear(); notes.addAll(TaskManager.getNotes(this)); adapter.notifyDataSetChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun delete(note: Note) {
        TaskManager.deleteNote(this, note.time)
        notes.remove(note)
        adapter.notifyDataSetChanged()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // Simple inline EditText fallback.
    private class AlertDialogInputView(context: android.content.Context) : android.widget.FrameLayout(context) {
        private val edit = android.widget.EditText(context).apply {
            hint = "Write your note…"
            setPadding(48, 32, 48, 32)
        }
        init { addView(edit) }
        fun textValue(): String = edit.text.toString().trim()
    }

    private class NoteAdapter(
        private val list: List<Note>,
        private val onDelete: (Note) -> Unit
    ) : RecyclerView.Adapter<NoteAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val text: TextView = v.findViewById(R.id.noteText)
            val date: TextView = v.findViewById(R.id.noteDate)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val n = list[position]
            holder.text.text = n.text
            holder.date.text = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(n.time))
            holder.itemView.setOnLongClickListener { onDelete(n); true }
        }

        override fun getItemCount() = list.size
    }
}
