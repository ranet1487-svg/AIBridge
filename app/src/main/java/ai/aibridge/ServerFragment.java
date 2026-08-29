package ai.aibridge;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

public class ServerFragment extends Fragment {

    private TextView txtStatus, txtLogs;
    private BridgeServer server;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LogBus.Listener listener = this::appendLog;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup p, @Nullable Bundle b) {
        View root = inf.inflate(R.layout.fragment_server, p, false);
        server = AIBridgeApplication.get().getBridgeServer();

        txtStatus = root.findViewById(R.id.txtStatus);
        txtLogs = root.findViewById(R.id.txtLogs);

        Button btnStart = root.findViewById(R.id.btnStart);
        Button btnStop = root.findViewById(R.id.btnStop);

        btnStart.setOnClickListener(v -> startServer());
        btnStop.setOnClickListener(v -> stopServer());

        refreshStatus();
        txtLogs.setText(LogBus.get().dump());

        LogBus.get().addListener(listener);
        return root;
    }

    private void startServer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // POST_NOTIFICATIONS already requested in Activity; foreground works.
        }
        Intent svc = new Intent(requireContext(), BridgeForegroundService.class);
        ContextCompat.startForegroundService(requireContext(), svc);
        ui.postDelayed(this::refreshStatus, 600);
    }

    private void stopServer() {
        requireContext().stopService(new Intent(requireContext(), BridgeForegroundService.class));
        ui.postDelayed(this::refreshStatus, 300);
    }

    private void refreshStatus() {
        if (server.isRunning()) {
            txtStatus.setText(getString(R.string.server_running, server.getPort()));
        } else {
            txtStatus.setText(R.string.server_stopped);
        }
    }

    private void appendLog(String line) {
        ui.post(() -> {
            txtLogs.append(line + "\n");
            // crude auto-scroll: trim if too large
            if (txtLogs.length() > 8000) {
                txtLogs.setText(LogBus.get().dump());
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        LogBus.get().removeListener(listener);
    }
}
