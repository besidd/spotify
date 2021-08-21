package com.app.spotify.other

data class Resource<out T>(val status: Status, val data: T? = null, val message: String? = null) {

    companion object {
        fun <T> success(data: T?) = Resource(Status.SUCCESS, data)
        fun <T> error(data: T?, message: String) = Resource(Status.ERROR, data, message)
        fun <T> loading(data: T?) = Resource(Status.LOADING, data)
        fun <T> empty(data: T?) = Resource(Status.EMPTY, data)
    }

}

enum class Status {
    SUCCESS,
    ERROR,
    LOADING,
    EMPTY
}