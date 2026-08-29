package ai.aibridge;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_NOTIF = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }

        TabLayout tabs = findViewById(R.id.tabLayout);
        ViewPager2 pager = findViewById(R.id.viewPager);
        pager.setAdapter(new ScreenAdapter(this));

        new TabLayoutMediator(tabs, pager, (tab, position) -> {
            switch (position) {
                case 0: tab.setText(R.string.tab_webviews); break;
                case 1: tab.setText(R.string.tab_server); break;
                default: tab.setText(R.string.copy_setup); break;
            }
        }).attach();
    }

    private static class ScreenAdapter extends FragmentStateAdapter {
        ScreenAdapter(AppCompatActivity a) { super(a); }
        @Override public int getItemCount() { return 3; }
        @Override public androidx.fragment.app.Fragment createFragment(int pos) {
            switch (pos) {
                case 0: return new WebViewsFragment();
                case 1: return new ServerFragment();
                default: return new SetupFragment();
            }
        }
    }
}
