package com.example.gomokuapp

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

// --- データクラス定義 ---

data class Move(
    @SerializedName("player_id") val playerId: String,
    @SerializedName("x_coord") val x: Int,
    @SerializedName("y_coord") val y: Int
)

data class GameStateResponse(
    @SerializedName("game_id") val gameId: Int,
    @SerializedName("status") val status: String,
    @SerializedName("player_1_id") val player1Id: String,
    @SerializedName("player_2_id") val player2Id: String?,
    @SerializedName("current_turn_id") val currentTurnId: String?,
    @SerializedName("winner_id") val winnerId: String?,
    @SerializedName("moves") val moves: List<Move>
)

data class FindGameResponse(
    @SerializedName("game_id") val gameId: Int,
    @SerializedName("role") val role: String
)

data class FindGameRequest(@SerializedName("my_player_id") val myPlayerId: String)
data class PlaceMoveRequest(
    @SerializedName("game_id") val gameId: Int,
    @SerializedName("player_id") val playerId: String,
    val x: Int,
    val y: Int
)

// --- API定義 ---

interface GomokuApi {
    @POST("api_find_game.php")
    suspend fun findGame(@Body request: FindGameRequest): Response<FindGameResponse>

    @GET("api_get_state.php")
    suspend fun getState(@Query("game_id") gameId: Int): Response<GameStateResponse>

    @POST("api_place_move.php")
    suspend fun placeMove(@Body request: PlaceMoveRequest): Response<Map<String, String>>
}

// --- メイン画面 ---

class MainActivity : AppCompatActivity() {
    // 接続先候補
    private val serverCandidates = listOf(
        "http://phpdb.homeserver1/gomoku/",
        "http://172.23.72.107/gomoku/"
    )

    // ビューの定義
    private lateinit var boardView: GomokuBoardView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button

    // ゲーム用変数
    private lateinit var api: GomokuApi // 初期化はサーバー決定後に遅らせる
    private var currentBaseUrl: String? = null
    private val myPlayerId = "android_" + UUID.randomUUID().toString().substring(0, 8)
    private var currentGameId: Int? = null
    private var myRole: String? = null
    private var pollingJob: Job? = null
    private var isMyTurn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Viewの取得
        boardView = findViewById(R.id.boardView)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)

        // 最初はボタンを押せないようにする（サーバー未定のため）
        startButton.isEnabled = false
        statusText.text = "サーバーを探しています..."

        // サーバー探索開始
        findActiveServer()
    }

    // サーバーを探し、見つかったらセットアップする関数
    private fun findActiveServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            var activeUrl: String? = null

            for (urlStr in serverCandidates) {
                if (isServerReachable(urlStr)) {
                    activeUrl = urlStr
                    break
                }
            }

            withContext(Dispatchers.Main) {
                if (activeUrl != null) {
                    currentBaseUrl = activeUrl
                    Log.d("ServerCheck", "接続成功: $currentBaseUrl")
                    statusText.text = "サーバーに接続しました"

                    setupGameDependencies()
                } else {
                    statusText.text = "エラー: 全サーバー接続不可"
                    Log.e("ServerCheck", "全サーバー接続不可")
                }
            }
        }
    }

    private fun setupGameDependencies() {
        try {
            // Retrofitの作成（決定したURLを使用）
            val retrofit = Retrofit.Builder()
                .baseUrl(currentBaseUrl!!) // activeUrlが入っている
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            api = retrofit.create(GomokuApi::class.java)

            // ボタンなどのリスナーをセット
            startButton.isEnabled = true
            startButton.setOnClickListener {
                findGame()
            }

            boardView.onCellClicked = { x, y ->
                if (currentGameId != null && isMyTurn) {
                    placeMove(x, y)
                } else {
                    Toast.makeText(this, "今は置けません", Toast.LENGTH_SHORT).show()
                }
            }

        } catch (e: Exception) {
            statusText.text = "設定エラー: ${e.message}"
        }
    }

    private fun isServerReachable(urlString: String): Boolean {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    private fun findGame() {
        lifecycleScope.launch {
            try {
                startButton.isEnabled = false
                statusText.text = "対戦相手を探しています..."

                val response = api.findGame(FindGameRequest(myPlayerId))
                if (response.isSuccessful && response.body() != null) {
                    val data = response.body()!!
                    currentGameId = data.gameId
                    myRole = data.role

                    val roleText = if(myRole == "player_1") "黒" else "白"
                    statusText.text = "ゲーム開始！あなたは $roleText です"
                    startPolling()
                } else {
                    statusText.text = "接続失敗: サーバーエラー"
                    startButton.isEnabled = true
                }
            } catch (e: Exception) {
                statusText.text = "エラー: ${e.message}"
                startButton.isEnabled = true
            }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            while (true) {
                fetchState()
                delay(2000)
            }
        }
    }

    private suspend fun fetchState() {
        val gameId = currentGameId ?: return
        try {
            val response = api.getState(gameId)
            if (response.isSuccessful && response.body() != null) {
                val state = response.body()!!

                boardView.player1Id = state.player1Id
                boardView.moves = state.moves
                boardView.invalidate() // 盤面再描画

                if (state.status == "waiting") {
                    statusText.text = "対戦相手を待っています..."
                    isMyTurn = false
                } else if (state.status == "finished") {
                    pollingJob?.cancel()
                    if (state.winnerId == myPlayerId) {
                        statusText.text = "あなたの勝利です！"
                    } else {
                        statusText.text = "あなたの敗北です..."
                    }
                    isMyTurn = false
                    startButton.isEnabled = true
                    startButton.text = "新しいゲーム"
                } else {
                    isMyTurn = (state.currentTurnId == myPlayerId)
                    val color = if (myRole == "player_1") "黒" else "白"
                    if (isMyTurn) {
                        statusText.text = "あなたの番です ($color)"
                    } else {
                        statusText.text = "相手の番です ($color)"
                    }
                }
            }
        } catch (e: Exception) {
            // ポーリング中のエラーはログ出力程度に留める
            Log.e("Gomoku", "State fetch error", e)
        }
    }

    private fun placeMove(x: Int, y: Int) {
        val gameId = currentGameId ?: return

        lifecycleScope.launch {
            try {
                val request = PlaceMoveRequest(gameId, myPlayerId, x, y)
                val response = api.placeMove(request)

                if (response.isSuccessful) {
                    fetchState()
                }
            } catch (e: Exception) {
                Toast.makeText(applicationContext, "通信エラー", Toast.LENGTH_SHORT).show()
            }
        }
    }
}