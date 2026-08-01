package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HospitalDao {
    @Query("SELECT * FROM hospitals")
    fun getAllHospitalsFlow(): Flow<List<Hospital>>

    @Query("SELECT * FROM hospitals")
    suspend fun getAllHospitals(): List<Hospital>

    @Query("SELECT * FROM hospitals WHERE id = :id")
    suspend fun getHospitalById(id: String): Hospital?

    @Query("SELECT * FROM hospitals WHERE id = :id")
    fun getHospitalByIdFlow(id: String): Flow<Hospital?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHospitals(hospitals: List<Hospital>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHospital(hospital: Hospital)

    @Update
    suspend fun updateHospital(hospital: Hospital)

    @Query("SELECT * FROM hospitals WHERE LOWER(phone) = LOWER(:phone) OR LOWER(emergencyPhone) = LOWER(:phone) LIMIT 1")
    suspend fun getHospitalByPhone(phone: String): Hospital?

    @Query("SELECT * FROM hospitals WHERE LOWER(websiteUrl) LIKE '%' || LOWER(:websiteUrl) || '%' LIMIT 1")
    suspend fun getHospitalByWebsite(websiteUrl: String): Hospital?

    @Query("DELETE FROM hospitals")
    suspend fun clearAllHospitals()
}

@Dao
interface IcuInventoryDao {
    @Query("SELECT * FROM icu_inventory")
    fun getAllInventoryFlow(): Flow<List<IcuInventory>>

    @Query("SELECT * FROM icu_inventory")
    suspend fun getAllInventory(): List<IcuInventory>

    @Query("SELECT * FROM icu_inventory WHERE hospitalId = :hospitalId")
    suspend fun getInventoryForHospital(hospitalId: String): List<IcuInventory>

    @Query("SELECT * FROM icu_inventory WHERE hospitalId = :hospitalId")
    fun getInventoryForHospitalFlow(hospitalId: String): Flow<List<IcuInventory>>

    @Query("SELECT * FROM icu_inventory WHERE id = :id")
    suspend fun getInventoryById(id: String): IcuInventory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInventoryList(inventory: List<IcuInventory>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInventory(inventory: IcuInventory)

    @Update
    suspend fun updateInventory(inventory: IcuInventory)

    @Query("DELETE FROM icu_inventory")
    suspend fun clearAllInventory()
}

@Dao
interface BookingDao {
    @Query("SELECT * FROM bookings ORDER BY heldAt DESC")
    fun getAllBookingsFlow(): Flow<List<Booking>>

    @Query("SELECT * FROM bookings WHERE id = :id")
    suspend fun getBookingById(id: String): Booking?

    @Query("SELECT * FROM bookings WHERE id = :id")
    fun getBookingByIdFlow(id: String): Flow<Booking?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooking(booking: Booking)

    @Update
    suspend fun updateBooking(booking: Booking)

    @Query("DELETE FROM bookings")
    suspend fun clearAllBookings()
}

@Dao
interface HospitalStaffAccountDao {
    @Query("SELECT * FROM hospital_accounts WHERE LOWER(email) = LOWER(:email) LIMIT 1")
    suspend fun getAccountByEmail(email: String): HospitalStaffAccount?

    @Query("SELECT * FROM hospital_accounts WHERE REPLACE(phone, ' ', '') = REPLACE(:phone, ' ', '') LIMIT 1")
    suspend fun getAccountByPhone(phone: String): HospitalStaffAccount?

    @Query("SELECT * FROM hospital_accounts WHERE hospitalId = :hospitalId LIMIT 1")
    suspend fun getAccountForHospital(hospitalId: String): HospitalStaffAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: HospitalStaffAccount)
}

@Dao
interface UserAccountDao {
    @Query("SELECT * FROM user_accounts WHERE LOWER(email) = LOWER(:email) LIMIT 1")
    suspend fun getAccountByEmail(email: String): UserAccount?

    @Query("SELECT * FROM user_accounts WHERE REPLACE(phone, ' ', '') = REPLACE(:phone, ' ', '') LIMIT 1")
    suspend fun getAccountByPhone(phone: String): UserAccount?

    @Query("SELECT * FROM user_accounts")
    suspend fun getAllAccounts(): List<UserAccount>

    @Query("SELECT * FROM user_accounts ORDER BY createdAt DESC")
    fun getAllAccountsFlow(): kotlinx.coroutines.flow.Flow<List<UserAccount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: UserAccount)
}

@Dao
interface SearchedLocationDao {
    @Query("SELECT * FROM searched_locations ORDER BY timestamp DESC LIMIT 3")
    fun getLast3LocationsFlow(): Flow<List<SearchedLocation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: SearchedLocation)

    @Query("DELETE FROM searched_locations WHERE name = :name")
    suspend fun deleteLocationByName(name: String)

    @Query("DELETE FROM searched_locations")
    suspend fun clearAll()
}

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments WHERE bookingId = :bookingId ORDER BY createdAt DESC")
    fun getPaymentsForBookingFlow(bookingId: String): Flow<List<Payment>>

    @Query("SELECT * FROM payments WHERE gatewayOrderId = :orderId LIMIT 1")
    suspend fun getPaymentByOrderId(orderId: String): Payment?

    @Query("SELECT * FROM payments WHERE bookingId = :bookingId AND status = 'SUCCESS' LIMIT 1")
    suspend fun getSuccessfulPaymentForBooking(bookingId: String): Payment?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(payment: Payment)

    @Update
    suspend fun updatePayment(payment: Payment)
}

