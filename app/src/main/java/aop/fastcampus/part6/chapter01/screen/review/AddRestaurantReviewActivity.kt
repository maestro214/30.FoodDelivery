package aop.fastcampus.part6.chapter01.screen.review

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import aop.fastcampus.part6.chapter01.data.entity.review.ReviewEntity
import aop.fastcampus.part6.chapter01.databinding.ActivityAddRestaurantReviewBinding
import aop.fastcampus.part6.chapter01.screen.review.camera.CameraActivity
import aop.fastcampus.part6.chapter01.screen.review.gallery.GalleryActivity
import aop.fastcampus.part6.chapter01.widget.adapter.PhotoListAdapter
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.koin.android.ext.android.inject

class AddRestaurantReviewActivity : AppCompatActivity() {

    private var imageUriList: ArrayList<Uri> = arrayListOf()

    private val auth by lazy { FirebaseAuth.getInstance() }

    private val storage: FirebaseStorage by inject()

    private val firestore: FirebaseFirestore by inject()

    private val photoListAdapter = PhotoListAdapter { uri -> removePhoto(uri) }

    private val restaurantTitle by lazy { intent.getStringExtra("restaurantTitle")!! }
    private val orderId by lazy { intent.getStringExtra("orderId")!! }

    companion object {
        fun newIntent(
            context: Context,
            orderId: String,
            restaurantTitle: String
        ) = Intent(context, AddRestaurantReviewActivity::class.java).apply {
            putExtra("orderId", orderId)
            putExtra("restaurantTitle", restaurantTitle)
        }

        const val PERMISSION_REQUEST_CODE = 1000
        const val GALLERY_REQUEST_CODE = 1001
        const val CAMERA_REQUEST_CODE = 1002
    }

    private lateinit var binding: ActivityAddRestaurantReviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddRestaurantReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
    }

    private fun initViews() = with(binding) {
        photoRecyclerView.adapter = photoListAdapter

        titleTextView.text = restaurantTitle

        toolbar.setNavigationOnClickListener {
            finish()
        }

        imageAddButton.setOnClickListener {
            showPictureUploadDialog()
        }

        submitButton.setOnClickListener {
            val title = binding.titleEditText.text.toString()
            val content = binding.contentEditText.text.toString()
            val userId = auth.currentUser?.uid.orEmpty()
            val rating = binding.ratingBar.rating

            showProgress()

            // ????????? ???????????? ????????? ????????? ????????? ??????
            if (imageUriList.isNotEmpty()) {
                lifecycleScope.launch {
                    val results = uploadPhoto(imageUriList)
                    afterUploadPhoto(results, title, content, rating, userId)
                }
            } else {
                uploadArticle(userId, title, content, rating, listOf())
            }
        }
    }

    private suspend fun uploadPhoto(uriList: List<Uri>) = withContext(Dispatchers.IO) {
        val uploadDeferred: List<Deferred<Any>> = uriList.mapIndexed { index, uri ->
            lifecycleScope.async {
                try {
                    val fileName = "image${index}.png"
                    return@async storage.reference.child("reviews/photo").child(fileName)
                        .putFile(uri)
                        .await()
                        .storage
                        .downloadUrl
                        .await()
                        .toString()
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@async Pair(uri, e)
                }
            }
        }
        return@withContext uploadDeferred.awaitAll()
    }

    private fun afterUploadPhoto(results: List<Any>, title: String, content: String, rating: Float, userId: String) {
        val errorResults = results.filterIsInstance<Pair<Uri, Exception>>()
        val successResults = results.filterIsInstance<String>()

        when {
            errorResults.isNotEmpty() && successResults.isNotEmpty() -> {
                photoUploadErrorButContinueDialog(errorResults, successResults, title, content, rating, userId)
            }
            errorResults.isNotEmpty() && successResults.isEmpty() -> {
                uploadError()
            }
            else -> {
                uploadArticle(userId, title, content, rating, successResults)
            }
        }
    }

    private fun uploadArticle(userId: String, title: String, content: String, rating: Float, imageUrlList: List<String>) {
        val reviewEntity = ReviewEntity(userId, title, System.currentTimeMillis(), content, rating, imageUrlList, orderId, restaurantTitle)
        firestore
            .collection("review")
            .add(reviewEntity)
        hideProgress()
        Toast.makeText(this, "????????? ??????????????? ????????? ???????????????.", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PERMISSION_REQUEST_CODE ->
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startGalleryScreen()
                } else {
                    Toast.makeText(this, "????????? ?????????????????????.", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun startGalleryScreen() {
        startActivityForResult(
            GalleryActivity.newIntent(this),
            GALLERY_REQUEST_CODE
        )
    }

    private fun startCameraScreen() {
        val intent = Intent(this, CameraActivity::class.java)
        startActivityForResult(intent, CAMERA_REQUEST_CODE)
    }

    private fun showProgress() {
        binding.progressBar.isVisible = true
    }

    private fun hideProgress() {
        binding.progressBar.isVisible = false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != Activity.RESULT_OK) {
            return
        }

        when (requestCode) {
            GALLERY_REQUEST_CODE -> {
                data?.let {
                    val uriList = it.getParcelableArrayListExtra<Uri>("uriList")
                    uriList?.let { list ->
                        imageUriList.addAll(list)
                        photoListAdapter.setPhotoList(imageUriList)
                    }
                } ?: kotlin.run {
                    Toast.makeText(this, "????????? ???????????? ???????????????.", Toast.LENGTH_SHORT).show()
                }
            }
            CAMERA_REQUEST_CODE -> {
                data?.let {
                    val uriList = it.getParcelableArrayListExtra<Uri>("uriList")
                    uriList?.let { list ->
                        imageUriList.addAll(list)
                        photoListAdapter.setPhotoList(imageUriList)
                    }
                } ?: kotlin.run {
                    Toast.makeText(this, "????????? ???????????? ???????????????.", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                Toast.makeText(this, "????????? ???????????? ???????????????.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPictureUploadDialog() {
        AlertDialog.Builder(this)
            .setTitle("????????????")
            .setMessage("??????????????? ????????? ???????????????")
            .setPositiveButton("?????????") { _, _ ->
                checkExternalStoragePermission {
                    startCameraScreen()
                }
            }
            .setNegativeButton("?????????") { _, _ ->
                checkExternalStoragePermission {
                    startGalleryScreen()
                }
            }
            .create()
            .show()
    }

    private fun checkExternalStoragePermission(uploadAction: () -> Unit) {
        when {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED -> {
                uploadAction()
            }
            shouldShowRequestPermissionRationale(android.Manifest.permission.READ_EXTERNAL_STORAGE) -> {
                showPermissionContextPopup()
            }
            else -> {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE)
            }
        }
    }

    private fun showPermissionContextPopup() {
        AlertDialog.Builder(this)
            .setTitle("????????? ???????????????.")
            .setMessage("????????? ???????????? ?????? ???????????????.")
            .setPositiveButton("??????") { _, _ ->
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE)
            }
            .create()
            .show()

    }

    private fun photoUploadErrorButContinueDialog(
        errorResults: List<Pair<Uri, Exception>>,
        successResults: List<String>,
        title: String,
        content: String,
        rating: Float,
        userId: String
    ) {
        AlertDialog.Builder(this)
            .setTitle("?????? ????????? ????????? ??????")
            .setMessage("???????????? ????????? ???????????? ????????????." + errorResults.map { (uri, _) ->
                "$uri\n"
            } + "???????????? ???????????? ????????? ???????????????????")
            .setPositiveButton("?????????") { _, _ ->
                uploadArticle(userId, title, content, rating, successResults)
            }
            .create()
            .show()
    }

    private fun uploadError() {
        Toast.makeText(this, "?????? ???????????? ??????????????????.", Toast.LENGTH_SHORT).show()
        hideProgress()
    }

    private fun removePhoto(uri: Uri) {
        imageUriList.remove(uri)
        photoListAdapter.setPhotoList(imageUriList)
    }

}
