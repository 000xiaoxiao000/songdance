package com.example.myapplication

import android.content.Context
import androidx.paging.PagingSource
import androidx.paging.PagingState

class AvatarImagePagingSource(
    private val context: Context,
    private val setName: String
) : PagingSource<Int, String>() {
    
    companion object {
        private const val PAGE_SIZE = 20
    }
    
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> {
        return try {
            val page = params.key ?: 0
            val allImages = AvatarImageManager.getAvailableImageNames(context, setName)
            
            val startIndex = page * PAGE_SIZE
            val endIndex = minOf(startIndex + PAGE_SIZE, allImages.size)
            
            val pageData = if (startIndex < allImages.size) {
                allImages.subList(startIndex, endIndex)
            } else {
                emptyList()
            }
            
            LoadResult.Page(
                data = pageData,
                prevKey = if (page == 0) null else page - 1,
                nextKey = if (endIndex >= allImages.size) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
    
    override fun getRefreshKey(state: PagingState<Int, String>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
