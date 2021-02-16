package com.epri.testbleproto;

import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.support.v4.app.NavUtils;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.view.MenuItem;

import java.util.Objects;


//public class MyPreferencesActivity extends PreferenceActivity {
public class MyPreferencesActivity extends AppCompatActivity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_settings);
        ActionBar actionBar = this.getSupportActionBar();
        if (actionBar != null)
        {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        //getFragmentManager().beginTransaction().replace(android.R.id.content, new MyPrefernceFragment()).commit();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home)
        {
            //finish();
            NavUtils.navigateUpFromSameTask(this);
        }
        return super.onOptionsItemSelected(item);
    }

    //    public static class MyPrefernceFragment extends PreferenceFragment
//    {
//        public void onCreate(final Bundle savedInstanceState)
//        {
//            super.onCreate(savedInstanceState);
//            addPreferencesFromResource(R.xml.preferences);
//        }
//    }
}

//public class MyPrefernceFragment extends PreferenceFragment
