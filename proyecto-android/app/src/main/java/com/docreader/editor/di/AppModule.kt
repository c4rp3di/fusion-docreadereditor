package com.docreader.editor.di

import android.content.Context
import androidx.room.Room
import com.docreader.editor.data.db.AppDatabase
import com.docreader.editor.data.db.DocumentDao
import com.docreader.editor.data.db.Migration1To2
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "docs.db")
            .addMigrations(Migration1To2())
            .build()

    @Provides
    fun documentDao(db: AppDatabase): DocumentDao = db.documentDao()
}
