package com.fawads.ai.ui.main

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fawads.ai.R
import com.fawads.ai.util.TaskItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskAdapter(
    private val onCompletionChanged: (TaskItem, Boolean) -> Unit,
    private val onDelete: (TaskItem) -> Unit
) : RecyclerView.Adapter<TaskAdapter.TaskViewHolder>() {

    private val items = mutableListOf<TaskItem>()

    fun submitTasks(tasks: List<TaskItem>) {
        items.clear()
        items.addAll(tasks)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_task, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        val task = items[position]
        holder.title.text = task.title
        holder.due.text = task.dueAt?.let {
            "Reminder · ${SimpleDateFormat("EEE, d MMM · h:mm a", Locale.getDefault()).format(Date(it))}"
        } ?: "No reminder set"

        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = task.completed
        holder.title.paintFlags = if (task.completed) {
            holder.title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            holder.title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        holder.due.alpha = if (task.completed) 0.55f else 1f
        holder.check.setOnCheckedChangeListener { _, checked ->
            if (checked != task.completed) onCompletionChanged(task, checked)
        }
        holder.delete.setOnClickListener { onDelete(task) }
    }

    override fun getItemCount(): Int = items.size

    class TaskViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val check: CheckBox = view.findViewById(R.id.taskCheck)
        val title: TextView = view.findViewById(R.id.taskTitle)
        val due: TextView = view.findViewById(R.id.taskDue)
        val delete: TextView = view.findViewById(R.id.taskDelete)
    }
}
