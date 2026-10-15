package pro.potoki.bekon.agent.shortcuts

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** List of shortcuts, plus add and delete. Tapping a row opens it. */
@Composable
fun ShortcutsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ShortcutStore(context) }
    var items by remember { mutableStateOf(store.list()) }
    var adding by remember { mutableStateOf(false) }
    fun reload() { items = store.list() }

    // Back from "Add shortcut" returns to the list (registered after the parent's handler, so it wins).
    androidx.activity.compose.BackHandler(enabled = adding) { adding = false }
    if (adding) {
        AddShortcutScreen(
            onBack = { adding = false },
            onSave = { title, kind, target ->
                try {
                    store.add(title, kind, target)
                    reload()
                    adding = false
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "could not add", Toast.LENGTH_SHORT).show()
                }
            },
        )
        return
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Shortcuts", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "Apps and links the agent can open without hunting for them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add") }
            Spacer(Modifier.height(8.dp))
            if (items.isEmpty()) {
                Text("Nothing saved yet.", Modifier.padding(top = 24.dp))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(items, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                try {
                                    store.open(item.id)
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message ?: "could not open", Toast.LENGTH_SHORT).show()
                                }
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    item.target,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { store.remove(item.id); reload() }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddShortcutScreen(
    onBack: () -> Unit,
    onSave: (title: String, kind: String, target: String) -> Unit,
) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf("app") }
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    val apps = remember { launchableApps(context) }
    val shown = apps.filter {
        val q = filter.trim().lowercase()
        q.isEmpty() || q in it.label.lowercase() || q in it.packageName.lowercase()
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Add shortcut", style = MaterialTheme.typography.titleLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (kind == "app") {
                    Button(onClick = { kind = "app" }) { Text("App") }
                    OutlinedButton(onClick = { kind = "url" }) { Text("Link") }
                } else {
                    OutlinedButton(onClick = { kind = "app" }) { Text("App") }
                    Button(onClick = { kind = "url" }) { Text("Link") }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (kind == "url") {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onSave(title, "url", url) },
                    enabled = title.isNotBlank() && url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save") }
            } else {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Filter") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(shown, key = { it.packageName }) { app ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                onSave(app.label, "app", app.packageName)
                            }.padding(vertical = 10.dp),
                        ) {
                            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
