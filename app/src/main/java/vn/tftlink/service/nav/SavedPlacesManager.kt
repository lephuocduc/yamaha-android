package vn.tftlink.service.nav

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.tftlink.data.model.location.SavedPlace

class SavedPlacesManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "nconnect_saved_places"
        private const val KEY_HOME = "key_home_place"
        private const val KEY_WORK = "key_work_place"
        private const val KEY_FAVORITES = "key_favorites_list"

        @Volatile
        private var instance: SavedPlacesManager? = null

        fun getInstance(context: Context): SavedPlacesManager {
            return instance ?: synchronized(this) {
                instance ?: SavedPlacesManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _homePlace = MutableStateFlow<SavedPlace?>(null)
    val homePlace: StateFlow<SavedPlace?> = _homePlace.asStateFlow()

    private val _workPlace = MutableStateFlow<SavedPlace?>(null)
    val workPlace: StateFlow<SavedPlace?> = _workPlace.asStateFlow()

    private val _favoritePlaces = MutableStateFlow<List<SavedPlace>>(emptyList())
    val favoritePlaces: StateFlow<List<SavedPlace>> = _favoritePlaces.asStateFlow()

    init {
        loadPlaces()
    }

    private fun loadPlaces() {
        prefs.getString(KEY_HOME, null)?.let {
            try { _homePlace.value = gson.fromJson(it, SavedPlace::class.java) } catch (_: Exception) {}
        }
        prefs.getString(KEY_WORK, null)?.let {
            try { _workPlace.value = gson.fromJson(it, SavedPlace::class.java) } catch (_: Exception) {}
        }
        prefs.getString(KEY_FAVORITES, null)?.let {
            try {
                val type = object : TypeToken<List<SavedPlace>>() {}.type
                _favoritePlaces.value = gson.fromJson(it, type)
            } catch (_: Exception) {}
        }
    }

    fun setHome(name: String, address: String, coordinate: LatLng) {
        val place = SavedPlace(name = name, address = address, latitude = coordinate.latitude, longitude = coordinate.longitude)
        _homePlace.value = place
        prefs.edit().putString(KEY_HOME, gson.toJson(place)).apply()
    }

    fun removeHome() {
        _homePlace.value = null
        prefs.edit().remove(KEY_HOME).apply()
    }

    fun setWork(name: String, address: String, coordinate: LatLng) {
        val place = SavedPlace(name = name, address = address, latitude = coordinate.latitude, longitude = coordinate.longitude)
        _workPlace.value = place
        prefs.edit().putString(KEY_WORK, gson.toJson(place)).apply()
    }

    fun removeWork() {
        _workPlace.value = null
        prefs.edit().remove(KEY_WORK).apply()
    }

    fun addFavorite(place: SavedPlace) {
        val list = _favoritePlaces.value.toMutableList()
        if (!list.any { it.id == place.id || (it.latitude == place.latitude && it.longitude == place.longitude) }) {
            list.add(place)
            _favoritePlaces.value = list
            prefs.edit().putString(KEY_FAVORITES, gson.toJson(list)).apply()
        }
    }

    fun removeFavorite(id: String) {
        val list = _favoritePlaces.value.filter { it.id != id }
        _favoritePlaces.value = list
        prefs.edit().putString(KEY_FAVORITES, gson.toJson(list)).apply()
    }
}
