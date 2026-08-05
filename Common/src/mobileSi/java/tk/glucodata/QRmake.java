/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+ and           */
/*      Sibionics GS1Sb sensors.                                                     */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Thu Apr 17 22:49:22 CEST 2025                                                 */


package tk.glucodata;

import static tk.glucodata.help.help;
import static tk.glucodata.settings.Settings.removeContentView;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeEncoder;

public class QRmake {
static private final String LOG_ID="QRmake";
private  static Bitmap bitmap(String myStringToEncode,int width,int height) throws WriterException {
            MultiFormatWriter multiFormatWriter = new MultiFormatWriter();
            BitMatrix bitMatrix = multiFormatWriter.encode(myStringToEncode, BarcodeFormat.QR_CODE,width,height);
            BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
            return barcodeEncoder.createBitmap(bitMatrix);
            }
public static void show(MainActivity act, String code) {
     ImageView image=new ImageView(act);
     int height=GlucoseCurve.getheight()-MainActivity.systembarTop-MainActivity.systembarBottom;
     int width=GlucoseCurve.getwidth()-MainActivity.systembarLeft-MainActivity.systembarRight;
     int availableWidth=Math.max(ClinicalUi.dp(act,180),width-ClinicalUi.dp(act,72));
     int availableHeight=Math.max(ClinicalUi.dp(act,180),height-ClinicalUi.dp(act,300));
     int qrSize=Math.min(availableWidth,availableHeight);
     try {
         image.setImageBitmap(bitmap(code,qrSize,qrSize));
         }
     catch(Throwable th) {
        Log.stack(LOG_ID,"setImageBitmap",th);
        }
     image.setAdjustViewBounds(true);
     image.setScaleType(ImageView.ScaleType.FIT_CENTER);
     image.setContentDescription(act.getString(R.string.connection_qr_title));

     Button close=ConnectionUi.headerButton(act,R.string.closename);
     LinearLayout helpRow=ClinicalUi.actionRow(act,act.getString(R.string.helpname),
           act.getString(R.string.connection_qr_help_hint));
     FrameLayout qrCard=new FrameLayout(act);
     qrCard.setBackground(ClinicalUi.surface(act,true,false));
     int cardPadding=ClinicalUi.dp(act,16);
     qrCard.setPadding(cardPadding,cardPadding,cardPadding,cardPadding);
     FrameLayout.LayoutParams imageParams=new FrameLayout.LayoutParams(qrSize,qrSize,
           Gravity.CENTER);
     qrCard.addView(image,imageParams);

     LinearLayout content=ConnectionUi.content(act);
     content.addView(ClinicalUi.header(act,
           act.getString(R.string.connection_qr_title),close));
     content.addView(ConnectionUi.intro(act,R.string.connection_qr_intro));
     LinearLayout.LayoutParams qrParams=new LinearLayout.LayoutParams(
           ViewGroup.LayoutParams.MATCH_PARENT,qrSize+cardPadding*2);
     qrParams.topMargin=ClinicalUi.dp(act,18);
     content.addView(qrCard,qrParams);
     content.addView(ClinicalUi.sectionLabel(act,
           act.getString(R.string.connection_support_section)));
     content.addView(ClinicalUi.card(act,helpRow));
     ScrollView screen=ConnectionUi.screen(act,content);
     ConnectionUi.fullScreen(act,screen);
     MainActivity.setonback(()->removeContentView(screen));
     close.setOnClickListener(view->MainActivity.doonback());
     helpRow.setOnClickListener(view->help(R.string.QRmirror,act));
      }

   }
