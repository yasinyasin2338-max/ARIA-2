package com.yasin.yconverter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yasin.yconverter.core.Category
import com.yasin.yconverter.core.ConverterRegistry
import com.yasin.yconverter.core.Operation
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YConverterTheme {
                YConverterScreen(viewModel)
            }
        }
    }
}

@Composable
private fun YConverterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YConverterScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectorVisible by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "Y Converter",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }

            item {
                OutlinedTextField(
                    value = state.inputText,
                    onValueChange = viewModel::setInput,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp),
                    label = { Text("متن فارسی یا انگلیسی را وارد کنید…") },
                )
            }

            item {
                val selectedMode = ConverterRegistry.byId(state.selectedModeId)?.meta
                Button(
                    onClick = { selectorVisible = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${selectedMode?.fa.orEmpty()} • ${selectedMode?.en.orEmpty()}")
                }
            }

            item {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SegmentedButton(
                        selected = state.operation == Operation.ENCODE,
                        onClick = { viewModel.setOperation(Operation.ENCODE) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text("Encode")
                    }
                    SegmentedButton(
                        selected = state.operation == Operation.DECODE,
                        onClick = { viewModel.setOperation(Operation.DECODE) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text("Decode")
                    }
                }
            }

            item {
                Button(
                    onClick = viewModel::convert,
                    enabled = !state.isProcessing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Text("تبدیل")
                    }
                }
            }

            state.errorMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = state.outputText.ifBlank { "نتیجه اینجا نمایش داده می‌شود." },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(state.outputText))
                            scope.launch { snackbarHostState.showSnackbar("کپی شد") }
                        },
                        enabled = state.canCopy,
                    ) {
                        Text("کپی")
                    }
                    OutlinedButton(onClick = viewModel::clear) {
                        Text("پاک کردن")
                    }
                }
            }
        }

        if (selectorVisible) {
            ModalBottomSheet(onDismissRequest = { selectorVisible = false }) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                ) {
                    Category.entries.forEach { category ->
                        item(key = "header-${category.name}") {
                            Text(
                                text = category.fa,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    top = 12.dp,
                                    end = 16.dp,
                                    bottom = 4.dp,
                                ),
                            )
                        }
                        items(
                            items = ConverterRegistry.all.filter { it.meta.category == category },
                            key = { it.meta.id },
                        ) { converter ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setMode(converter.meta.id)
                                        selectorVisible = false
                                    },
                            ) {
                                ListItem(
                                    headlineContent = { Text(converter.meta.fa) },
                                    supportingContent = { Text(converter.meta.en) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
