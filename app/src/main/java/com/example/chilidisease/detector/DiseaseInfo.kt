package com.example.chilidisease.detector

object DiseaseInfo {

    fun displayName(label: String): String {
        return when (normalize(label)) {
            "tidak_ada_cabai" -> "Tidak Ada Cabai Terdeteksi"
            "tidak_yakin" -> "Tidak Yakin"

            "sehat", "healthy", "healty" -> "Sehat"

            "antraknosa",
            "anthraknosa",
            "patek",
            "antraknosa_patek",
            "anthraknosa_patek" -> "Antraknosa (Patek)"

            "busuk_buah" -> "Busuk Buah"
            "layu_fusarium" -> "Layu Fusarium"

            "lalat_buah" -> "Lalat Buah"

            "cercospora",
            "cerocospora",
            "bercak_daun_cercospora",
            "bercak_daun_cerocospora" -> "Cercospora"

            else -> label.replace("_", " ")
        }
    }

    fun description(label: String): String {
        return when (normalize(label)) {
            "tidak_ada_cabai" ->
                "Tidak ada objek cabai yang terdeteksi pada kamera. Arahkan kamera ke buah cabai terlebih dahulu agar sistem dapat melakukan klasifikasi penyakit."

            "tidak_yakin" ->
                "Objek cabai terdeteksi, tetapi tingkat keyakinan model masih rendah. Coba dekatkan kamera, perbaiki pencahayaan, atau ambil gambar dari sudut yang lebih jelas."

            "sehat", "healthy", "healty" ->
                "Cabai terdeteksi dalam kondisi sehat. Berdasarkan hasil klasifikasi model, tidak terlihat indikasi kuat terhadap penyakit utama pada buah cabai."

            "antraknosa",
            "anthraknosa",
            "patek",
            "antraknosa_patek",
            "anthraknosa_patek" ->
                "Antraknosa atau patek adalah penyakit cabai yang umumnya ditandai dengan bercak cekung berwarna gelap pada buah. Penyakit ini sering disebabkan oleh jamur Colletotrichum sp. dan mudah berkembang pada kondisi lembap."

            "busuk_buah" ->
                "Busuk buah adalah penyakit pada cabai yang menyebabkan buah menjadi lunak, membusuk, berubah warna, berair, atau muncul bercak gelap. Penyakit ini biasanya dipicu oleh infeksi jamur atau bakteri, terutama pada kondisi lingkungan yang lembap."

            "layu_fusarium" ->
                "Layu Fusarium adalah penyakit tanaman cabai yang menyerang sistem pembuluh tanaman. Gejalanya dapat berupa daun menguning, tanaman layu, pertumbuhan terhambat, dan pada kondisi berat tanaman dapat mati."

            "lalat_buah" ->
                "Lalat buah adalah serangan hama pada cabai yang terjadi ketika lalat buah meletakkan telur di dalam buah. Gejalanya dapat berupa titik bekas tusukan, buah melunak, berubah warna, membusuk dari bagian dalam, dan rontok sebelum panen."

            "cercospora",
            "cerocospora",
            "bercak_daun_cercospora",
            "bercak_daun_cerocospora" ->
                "Cercospora adalah penyakit bercak daun pada tanaman cabai yang disebabkan oleh jamur Cercospora sp. Gejalanya berupa bercak bulat kecil pada daun, bagian tengah bercak berwarna abu-abu atau pucat, tepi bercak lebih gelap, daun menguning, dan dapat gugur jika serangan berat."

            else ->
                "Penyakit terdeteksi berdasarkan hasil klasifikasi model. Periksa kembali kondisi buah cabai secara langsung untuk memastikan gejala visualnya."
        }
    }

    private fun normalize(label: String): String {
        return label
            .lowercase()
            .trim()
            .replace(" ", "_")
            .replace("-", "_")
            .replace("/", "_")
            .replace("(", "")
            .replace(")", "")
    }
}