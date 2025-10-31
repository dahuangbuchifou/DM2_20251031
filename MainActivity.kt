package com.damaihelper.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.damaihelper.R
import com.damaihelper.databinding.ActivityMainBinding
import com.damaihelper.model.TicketTask
import com.damaihelper.service.TicketGrabbingAccessibilityService
import com.damaihelper.utils.AccessibilityUtils
import com.damaihelper.utils.AppUtils

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var taskAdapter: TaskAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        setupRecyclerView()
        observeViewModel()
        checkServiceStatus()
    }
    
    override fun onResume() {
        super.onResume()
        checkServiceStatus()
    }
    
    private fun setupUI() {
        binding.btnCreateTask.setOnClickListener {
            startActivity(Intent(this, TaskConfigActivity::class.java))
        }
        
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
    
    private fun setupRecyclerView() {
        taskAdapter = TaskAdapter(
            onStartClick = { task ->
                if (checkPermissions()) {
                    viewModel.startTask(task)
                }
            },
            onDeleteClick = { task ->
                viewModel.deleteTask(task)
                Toast.makeText(this, getString(R.string.task_deleted), Toast.LENGTH_SHORT).show()
            }
        )
        
        binding.rvTasks.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = taskAdapter
        }
    }
    
    private fun observeViewModel() {
        viewModel.tasks.observe(this, Observer { tasks ->
            taskAdapter.submitList(tasks)
            binding.tvEmptyTasks.visibility = if (tasks.isEmpty()) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        })
    }
    
    private fun checkServiceStatus() {
        val isAccessibilityEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(
            this, TicketGrabbingAccessibilityService::class.java
        )
        
        val isDamaiInstalled = AppUtils.isAppInstalled(this, "cn.damai")
        
        binding.tvAccessibilityStatus.text = if (isAccessibilityEnabled) {
            "无障碍服务：已启用"
        } else {
            "无障碍服务：未启用"
        }
        
        binding.tvAccessibilityStatus.setTextColor(
            getColor(if (isAccessibilityEnabled) R.color.success_green else R.color.error_red)
        )
        
        binding.tvDamaiStatus.text = if (isDamaiInstalled) {
            "大麦App：已安装"
        } else {
            "大麦App：未检测到"
        }
        
        binding.tvDamaiStatus.setTextColor(
            getColor(if (isDamaiInstalled) R.color.success_green else R.color.error_red)
        )
    }
    
    private fun checkPermissions(): Boolean {
        val isAccessibilityEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(
            this, TicketGrabbingAccessibilityService::class.java
        )
        
        if (!isAccessibilityEnabled) {
            Toast.makeText(this, getString(R.string.accessibility_not_enabled), Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return false
        }
        
        val isDamaiInstalled = AppUtils.isAppInstalled(this, "cn.damai")
        if (!isDamaiInstalled) {
            Toast.makeText(this, getString(R.string.damai_not_installed), Toast.LENGTH_LONG).show()
            return false
        }
        
        return true
    }
}
