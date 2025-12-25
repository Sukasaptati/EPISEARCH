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

![Screenshot_2025-12-25-18-41-04-85_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/8f8094b6-4d04-4d1c-8daa-10c2e648d308)
![Screenshot_2025-12-25-18-41-17-14_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/b7e3b968-2f50-4109-9d0b-6d8c0bd5aa8a)
![Screenshot_2025-12-25-18-42-10-08_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/2b052fe5-638b-4fbf-89b8-92672a7cfe25)
![Screenshot_2025-12-25-18-41-21-45_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/a18540aa-f623-4d94-ab64-205b3f9c7022)
![Screenshot_2025-12-25-18-41-26-69_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/46b856dd-2dd6-41b4-9170-82a2fea3073f)

![Screenshot_2025-12-25-10-05-11-34](https://github.com/user-attachments/assets/0b92446c-2f6e-4e9d-88a2-d072863f285e)
![IMG_20251224_174033](https://github.com/user-attachments/assets/a5536167-26b0-4a8e-bfe4-a6caa7173b8e)
![Screenshot_2025-12-25-19-25-51-28](https://github.com/user-attachments/assets/13c7fe29-98c5-4c30-8009-23361bcd5246)
