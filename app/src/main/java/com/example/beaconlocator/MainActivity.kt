package com.example.beaconlocator

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.AttributeSet
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.ui.AppBarConfiguration
import android.view.View
import com.example.beaconlocator.databinding.ActivityMainBinding

import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Observer
import org.altbeacon.beacon.*
import java.io.IOException
import org.altbeacon.beacon.Region
import java.lang.Integer.max
import java.lang.Math.min
import java.util.*
import kotlin.concurrent.schedule

// DAJ To open this file, run:
// ~/AndroidStudio/android-studio/bin$ ./studio.sh then open the "beaconlocator" directory (not a file in the directory)

// @JvmOverloads automatically builds the constructors we need
class CustomDrawableView @JvmOverloads constructor(context: Context,
                                                   attrs: AttributeSet? = null,
                                                   defStyleAttr: Int = 0) : View(context) {

    var viewHeightPixels = 0
    var viewWidthPixels = 0
    val maxRange = 13.0             //    Range for positioning is 0 through 15.25, with the largest ever actually observed being 12.59
    var circleRadius = 0
    var sensorName = ""
    var lastSeenTime = System.currentTimeMillis()

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        var displayMetrics = this.getResources().getDisplayMetrics();
        circleRadius = displayMetrics.widthPixels / 10

        viewHeightPixels = height
        viewWidthPixels = width
        x = calcX()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = 42.0f
        typeface = Typeface.create( "", Typeface.BOLD)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        paint.color = Color.RED
        paint.style = Paint.Style.FILL
        canvas.drawCircle(circleRadius.toFloat(), circleRadius.toFloat(), circleRadius.toFloat(), paint)

        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(circleRadius.toFloat(), circleRadius.toFloat(), circleRadius.toFloat(), paint)

        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = 1f

        // Draw the text labels.
        val centerPoint = PointF(circleRadius.toFloat(),circleRadius.toFloat())
        canvas.drawText(sensorName, centerPoint.x, centerPoint.y + paint.textSize/4, paint)
    }

    private fun calcY(sensorDistance: Float) : Float {
        return (((maxRange - sensorDistance) * viewHeightPixels) / maxRange - circleRadius).toFloat()
    }

    private fun calcX() : Float {
        return (viewWidthPixels / 2 - circleRadius).toFloat()
    }

    fun moveY(newval: Float) {
        y = calcY(newval)
    }

    fun moveX(){
        x = calcX()
    }
}


class MainActivity : AppCompatActivity() {

    val TAG = "MainActivity"

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    // Map bluetooth address to view
    private val beaconMap: MutableMap<String, CustomDrawableView> = mutableMapOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timer().schedule(2000, 2000) {
            deleteLostBeacons()
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        var displayMetrics = this.getResources().getDisplayMetrics();
        println("This is a test: ${displayMetrics.widthPixels} x ${displayMetrics.heightPixels}")

        checkPermissions();
        startRangingBeacons()
        saveTextFile(this, "This is a test", "text/csv", "TestFile.csv")
    }

    private fun deleteLostBeacons() {
        // post a request to the message loop so this runs on the UI thread
        Handler(Looper.getMainLooper()).post(object : Runnable {
            override fun run() {
                // DAJ here
                val rootLayout = findViewById<View>(R.id.main_layout) as ConstraintLayout

                val iter = beaconMap.iterator()
                for(e in iter) {
                    if(e.component2().lastSeenTime < System.currentTimeMillis() - 5000) {
                        // Delete the view from the root layout
                        rootLayout.removeView(e.component2())
                        // and remove it from the map by removing it from the iterator
                        iter.remove()
                    }
                }
            }
        })
    }

    @Throws(IOException::class)
    fun saveTextFile(
        context: Context, msg: String,
        mimeType: String, displayName: String
    ): Uri {

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val resolver = context.contentResolver
        var uri: Uri? = null

        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Failed to create new MediaStore record.")

            resolver.openOutputStream(uri)?.use {
                it.write(msg.toByteArray())
            } ?: throw IOException("Failed to open output stream.")

            return uri

        } catch (e: IOException) {
            uri?.let { orphanUri ->
                // Don't leave an orphan entry in the MediaStore
                resolver.delete(orphanUri, null, null)
            }
            throw e
        }
    }

    fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestMultiplePermissions.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
        else{
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestBluetooth.launch(enableBtIntent)
        }
    }

    private var requestBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            //granted
            Log.d("Requesting Permission", "Granted")
        }else{
            //deny
            Log.d("Requesting Permission", "Denied")
        }
    }

    private val requestMultiplePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.entries.forEach {
                Log.d("Permission entries: ", "${it.key} = ${it.value}")
            }
        }

    fun startRangingBeacons() {
        val region = Region("radius-uuid", null, null, null)
//        val region = Region("all-beacons-region", null, null, null)
        val beaconManager =  BeaconManager.getInstanceForApplication(this)
        beaconManager.setEnableScheduledScanJobs(false)
        beaconManager.setBackgroundBetweenScanPeriod(0)
        beaconManager.setBackgroundScanPeriod(1100)

        // Clear the altBeacon parser and add the parser for iBeacons instead.
        beaconManager.getBeaconParsers().clear()
        beaconManager.getBeaconParsers().add(BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"))

        beaconManager.getRegionViewModel(region).rangedBeacons.observeForever(rangingObserver)
        beaconManager.startRangingBeacons(region)
    }

    val rangingObserver = Observer<Collection<Beacon>> { beacons ->
        Log.d(TAG, "Ranged: ${beacons.count()} beacons")
        for (beacon: Beacon in beacons) {
            Log.d(TAG, "$beacon about ${beacon.distance} meters away")
                updateSensorDisplay(beacon)
        }
    }

    fun updateSensorDisplay(beacon: Beacon) {

        var theView: CustomDrawableView?

        // If the beacon hasn't been seen before, create a new view and add it to the map
        if(beaconMap.containsKey(beacon.bluetoothAddress)) {
            theView = beaconMap.get(beacon.bluetoothAddress)
        }
        else {
            theView = makeNewSensorView(beacon)
            beaconMap.put(beacon.bluetoothAddress, theView)
        }

        if (theView != null) {
            theView.moveY(beacon.distance.toFloat())
            theView.moveX()
            theView.lastSeenTime = System.currentTimeMillis()
        }
    }

    fun makeNewSensorView(beacon: Beacon) : CustomDrawableView {
        // make a new view
        val rootLayout = findViewById<View>(R.id.main_layout) as ConstraintLayout
        val view = this.layoutInflater.inflate(R.layout.sensor_custom_node, null) as CustomDrawableView

        if(beacon.bluetoothName.length <= "Sensor00".length) {
            view.sensorName = beacon.bluetoothName
        }
        else {
            view.sensorName = "Unk" + beacon.bluetoothName.substring(beacon.bluetoothName.length - 4)
        }
        rootLayout.addView(view)
        return view
    }
}
