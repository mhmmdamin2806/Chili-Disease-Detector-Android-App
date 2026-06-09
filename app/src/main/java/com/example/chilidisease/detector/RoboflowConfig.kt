package com.example.chilidisease.detector

object RoboflowConfig {

    /*
     * Model Roboflow yang sudah trained:
     * https://app.roboflow.com/rizky-vayvp/cabai-okbic/models/cabai-okbic/4
     */
    const val MODEL_ID = "cabai-okbic"
    const val MODEL_VERSION = "4"

    /*
     * Isi dengan PRIVATE API KEY dari workspace Roboflow kamu.
     */
    const val API_KEY = "ISI_PRIVATE_API_KEY_KAMU"

    const val BASE_URL = "https://serverless.roboflow.com/"

    const val MIN_CONFIDENCE = 0.50f

    /*
     * Healthy harus sangat yakin supaya cabai sakit tidak mudah dianggap sehat.
     */
    const val HEALTHY_MIN_CONFIDENCE = 0.90f
}