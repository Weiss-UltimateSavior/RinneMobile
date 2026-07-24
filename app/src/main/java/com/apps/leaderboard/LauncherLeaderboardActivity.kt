package com.apps.leaderboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.apps.LauncherActivity
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.yuki.yukihub.R
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge
import com.yuki.yukihub.util.TimeFormatUtil

class LauncherLeaderboardActivity : AppCompatActivity() {
    private lateinit var adapter: LauncherLeaderboardAdapter; private lateinit var topThree: FrameLayout; private lateinit var state: TextView
    override fun onCreate(savedInstanceState: Bundle?) { LauncherActivity.applySavedToneMode(this); super.onCreate(savedInstanceState); configureWindow(); setContentView(R.layout.activity_launcher_leaderboard); LauncherTabletPortraitScaler.applyActivityContent(this); val root = findViewById<View>(R.id.leaderboardRoot); val l=root.paddingLeft; val t=root.paddingTop; val r=root.paddingRight; val b=root.paddingBottom; root.setOnApplyWindowInsetsListener { v,i -> v.setPadding(l,t+i.systemWindowInsetTop,r,b+i.systemWindowInsetBottom); i }; root.requestApplyInsets(); LauncherTheme.applyPrimaryTone(root); adapter=LauncherLeaderboardAdapter(); findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.leaderboardList).apply { layoutManager=LinearLayoutManager(this@LauncherLeaderboardActivity); adapter=this@LauncherLeaderboardActivity.adapter }; topThree=findViewById(R.id.leaderboardTopThree); state=findViewById(R.id.leaderboardState); LauncherAuthBridge.fetchPlayTimeLeaderboard(this, object: LauncherAuthBridge.LeaderboardCallback { override fun onSuccess(entries: List<LauncherAuthBridge.LeaderboardEntry>)=show(entries); override fun onError(message:String){ show(emptyList()); state.text="排行榜暂不可用"; state.visibility=View.VISIBLE } }) }
    private fun show(source: List<LauncherAuthBridge.LeaderboardEntry>?) { val entries=(1..15).map { rank -> source?.firstOrNull { it.rank==rank } ?: LauncherAuthBridge.LeaderboardEntry(rank,"暂无排名",-1) }; val ids=arrayOf(intArrayOf(R.id.leaderboardFirst,R.id.leaderboardFirstRank,R.id.leaderboardFirstName,R.id.leaderboardFirstDuration),intArrayOf(R.id.leaderboardSecond,R.id.leaderboardSecondRank,R.id.leaderboardSecondName,R.id.leaderboardSecondDuration),intArrayOf(R.id.leaderboardThird,R.id.leaderboardThirdRank,R.id.leaderboardThirdName,R.id.leaderboardThirdDuration)); repeat(3) { i -> bind(entries[i],ids[i]) }; topThree.visibility=View.VISIBLE; state.visibility=View.GONE; adapter.submit(entries.drop(3)) }
    private fun bind(entry: LauncherAuthBridge.LeaderboardEntry, ids:IntArray) { findViewById<View>(ids[0]).visibility=View.VISIBLE; findViewById<ImageView>(ids[1]).imageTintList=ColorStateList.valueOf(ContextCompat.getColor(this, if(entry.rank==1) R.color.launcher_rank_gold_color else if(entry.rank==2) R.color.launcher_rank_silver_color else R.color.launcher_rank_bronze_color)); findViewById<TextView>(ids[2]).text=entry.username; findViewById<TextView>(ids[3]).apply { visibility=if(entry.totalDurationMs<0) View.GONE else View.VISIBLE; if(entry.totalDurationMs>=0) text=TimeFormatUtil.playTime(entry.totalDurationMs) } }
    private fun configureWindow(){ window.apply { clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN); addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS); statusBarColor=Color.TRANSPARENT; navigationBarColor=ContextCompat.getColor(this@LauncherLeaderboardActivity,R.color.launcher_bg_color); decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN } }
    override fun attachBaseContext(newBase: Context){ super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase)) }
}
