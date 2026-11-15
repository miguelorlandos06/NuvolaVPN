package com.nuvola.vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: TunnelUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("NuvolaVPN", fontWeight = FontWeight.SemiBold) })
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ESTADO", style = MaterialTheme.typography.labelMedium)
                    Text(state.status.toString(), style = MaterialTheme.typography.titleLarge)
                }
            }
            
            Spacer(Modifier.weight(1f))
            
            Button(
                onClick = { if (state.status is TunnelStatus.Connected) onDisconnect() else onConnect() },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("CONECTAR / DESCONECTAR", fontSize = 18.sp)
            }
        }
    }
}
