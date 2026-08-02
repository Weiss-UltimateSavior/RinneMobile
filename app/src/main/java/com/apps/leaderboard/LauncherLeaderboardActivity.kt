package com.apps.leaderboard

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.apps.LauncherActivity
import com.core.databinding.ActivityLauncherLeaderboardBinding
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.launcherbridge.LauncherAuthBridge
import com.core.launcherbridge.LeaderboardCallback
import com.core.launcherbridge.LeaderboardEntry
import com.core.util.TimeFormatUtil

class LauncherLeaderboardActivity : AppCompatActivity() {
    private lateinit var adapter: LauncherLeaderboardAdapter; private lateinit var binding: ActivityLauncherLeaderboardBinding
    override fun onCreate(savedInstanceState: Bundle?) { LauncherActivity.applySavedToneMode(this); super.onCreate(savedInstanceState); configureWindow(); binding = ActivityLauncherLeaderboardBinding.inflate(layoutInflater); setContentView(binding.root); LauncherTabletPortraitScaler.applyActivityContent(this); val l=binding.root.paddingLeft; val t=binding.root.paddingTop; val r=binding.root.paddingRight; val b=binding.root.paddingBottom; binding.root.setOnApplyWindowInsetsListener { v,i -> v.setPadding(l,t+i.systemWindowInsetTop,r,b+i.systemWindowInsetBottom); i }; binding.root.requestApplyInsets(); LauncherTheme.applyPrimaryTone(binding.root); adapter=LauncherLeaderboardAdapter(); binding.leaderboardList.apply { layoutManager=LinearLayoutManager(this@LauncherLeaderboardActivity); adapter=this@LauncherLeaderboardActivity.adapter }; LauncherAuthBridge.fetchPlayTimeLeaderboard(this, object: LeaderboardCallback { override fun onSuccess(entries: List<LeaderboardEntry>)=show(entries); override fun onError(message:String){ show(emptyList()); binding.leaderboardState.setText(R.string.social_leaderboard_unavailable); binding.leaderboardState.visibility=View.VISIBLE } }) }
    private fun show(source: List<LeaderboardEntry>?) { val entries=(1..15).map { rank -> source?.firstOrNull { it.rank==rank } ?: LeaderboardEntry(rank,getString(R.string.social_no_rank),-1) }; val groups=arrayOf(arrayOf<View>(binding.leaderboardFirst,binding.leaderboardFirstRank,binding.leaderboardFirstName,binding.leaderboardFirstDuration),arrayOf<View>(binding.leaderboardSecond,binding.leaderboardSecondRank,binding.leaderboardSecondName,binding.leaderboardSecondDuration),arrayOf<View>(binding.leaderboardThird,binding.leaderboardThirdRank,binding.leaderboardThirdName,binding.leaderboardThirdDuration)); repeat(3) { i -> bind(entries[i],groups[i]) }; binding.leaderboardTopThree.visibility=View.VISIBLE; binding.leaderboardState.visibility=View.GONE; adapter.submit(entries.drop(3)) }
    private fun bind(entry: LeaderboardEntry, views:Array<View>) { views[0].visibility=View.VISIBLE; (views[1] as ImageView).imageTintList=ColorStateList.valueOf(ContextCompat.getColor(this, if(entry.rank==1) R.color.launcher_rank_gold_color else if(entry.rank==2) R.color.launcher_rank_silver_color else R.color.launcher_rank_bronze_color)); (views[2] as TextView).text=entry.username; (views[3] as TextView).apply { visibility=if(entry.totalDurationMs<0) View.GONE else View.VISIBLE; if(entry.totalDurationMs>=0) text=TimeFormatUtil.playTime(entry.totalDurationMs) } }
    private fun configureWindow() {
        com.apps.LauncherEdgeToEdgeHelper.apply(this)
    }
    override fun attachBaseContext(newBase: Context){ super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase)) }
}
