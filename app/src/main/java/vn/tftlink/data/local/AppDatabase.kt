package vn.tftlink.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import vn.tftlink.data.model.trip.TripRecord

@Dao
interface TripDao {
    @Query("SELECT * FROM trip_records ORDER BY startTime DESC")
    fun getAllTrips(): Flow<List<TripRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripRecord)

    @Query("DELETE FROM trip_records WHERE id = :tripId")
    suspend fun deleteTrip(tripId: String)

    @Query("DELETE FROM trip_records")
    suspend fun clearAllTrips()
}

@Database(entities = [TripRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nconnect_database.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}

