package com.aura.ui.evolution

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BeliefsScreen(viewModel: BeliefsViewModel = hiltViewModel()) {
    val beliefs by viewModel.beliefs.collectAsStateWithLifecycle()
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(beliefs) { belief ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { viewModel.select(belief.id) }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("${belief.subject} — ${belief.predicate}", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(belief.valueJson, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row {
                        Text("conf: ${belief.confidence}", style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("status: ${belief.status}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
