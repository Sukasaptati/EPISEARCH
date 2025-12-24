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

![Screenshot_2025-12-18-10-45-39-99_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/873556fa-822a-48bc-8929-9718fa7e2881)
![Screenshot_2025-12-18-10-45-35-61_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/4c6c7a5d-17b2-41ff-932b-bf68b6dea086)
![Screenshot_2025-12-18-10-43-55-88_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/1414811b-75f4-42e7-abd4-1d566cc9a6be)
![Screenshot_2025-12-15-12-40-03-93_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/2a77f211-9176-4972-b5f3-94f06b46512e)
![Screenshot_2025-12-15-12-39-31-80_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/e7ce2796-6dfe-437b-b13d-f25b52638032)
![Screenshot_2025-12-15-12-39-24-52_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/c53f29fc-8626-457a-b41e-8d98fe68981b)
![Screenshot_2025-12-15-12-39-12-48_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/d7a3b932-5c87-4cef-93b4-3bafa64bc88a)
![Screenshot_2025-12-15-12-38-00-25_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/1439c319-c606-49df-bd62-557f5180e291)
![Screenshot_2025-12-16-19-08-31-07_7a5391456ddf15713cd09dfbd75e8325](https://github.com/user-attachments/assets/91c90f73-d26d-4655-aed7-50d76cd8214a)
![Screenshot_2025-12-16-19-07-44-40_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/4b6b5a53-8b95-4028-9dca-2a47d64d64ac)
![Screenshot_2025-12-16-19-07-35-93_574837b0a387c55e346b4d9fa01d2246](https://github.com/user-attachments/assets/99014ffe-3c0e-4a7c-a356-a85012814c74)
