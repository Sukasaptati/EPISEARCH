Android apk for searching Latin and Greek inscriptions and papyri

Sources

  Latin Epigraphy: http://db.edcs.eu/epigr/epi.php
  
  Greek Inscriptions: https://inscriptions.packhum.org
  
  Papyri: https://papyri.info

Use Link Database Files in the top left menu to link the apk to the databases stored on your device.

Use the dropdown menu at the top to choose databases. 

Use the first search bar to look for inscriptions that contain the keywords you want, and the second one for inscriptions without the keywords. Separate multiple keywords with '&&'. Keywords will be highlighted in red in the search results.

The scan button allows you to take a photo or load a local image for OCR (app camera permissions are required, otherwise the app will crash). The pop-up menu in the upper right corner allows you to choose between offline and online recognition. Offline recognition uses the Tessera library, and it converts the image to black and white before recognition to reduce background noise (currently, the recognition quality is poor). Online recognition uses Google's Cloud Vision API, offering much better results; free to use for 1000 times every month. The API can be obtained from the Google Cloud console.You can set the API key in the upper left menu.

If you no longer need the image, you can click the "X" in the upper right corner of the image. You can also save the image if you want.

You can choose offline translation, online translation and AI smart translation. Offline translation is available via Google Translate App's offline mode. Install Google Translate on your phone and download Latin and Greek language packs. AI translation is offered by gemini, and uses gemini api key (from ai studio, currently 15 requests per minute for gree tier). I have also added the api offered by ChatAnywhere project, which offers access to GPT 3.5 or 4.0. You can choose one mode of translation in the setting menu (in the top left of the window).

Aeneas AI model for restoring damaged inscriptions is integrated. When you have loaded and ocred a picture, but are faced with uncertainties, click the "pencil" button to use Aeneas model for restoration (running on kaggle, ngrok auth keys and domains needed).

![IMG_20251224_174002](https://github.com/user-attachments/assets/09d8c81e-deb1-4f2f-ac2c-521ed4dea2ee)
![Screenshot_2025-12-25-10-05-11-34](https://github.com/user-attachments/assets/dc2dea2d-c04a-4537-8545-bcbbc03ad88c)
![Screenshot_2025-12-25-18-41-04-85_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/f167d759-e0d1-4385-9178-9acdd2fc2537)
![Screenshot_2025-12-25-18-41-17-14_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/34e135de-8ed8-4e2e-bba8-f825d171952c)
![Screenshot_2025-12-25-18-41-21-45_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/bf8f6600-a036-47aa-be24-9e3e787887af)
![Screenshot_2025-12-25-18-41-26-69_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/62cbb839-4824-416d-b621-cb4df4aa73f3)
![Screenshot_2025-12-25-18-42-10-08_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/9dc502e9-31da-4ef0-a8cd-3b1d4c523cbe)
![Screenshot_2025-12-24-14-41-47-42_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/c53f4f04-6c0c-40bb-94b7-27d59ce472b5)

