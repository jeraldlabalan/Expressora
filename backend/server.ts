import express, { Request, Response } from "express";
import bodyParser from "body-parser";
import cors from "cors";
import dotenv from "dotenv";
import nodemailer from "nodemailer";
import { OAuth2Client } from "google-auth-library";
import admin from "firebase-admin";
import crypto from "crypto";
import path from "path";

dotenv.config();
const app = express();
const PORT: number = Number(process.env.PORT) || 3000;

app.use(cors());
app.use(bodyParser.json());

if (!admin.apps.length) {
  console.log("🔥 Initializing Firebase Admin SDK...");
  admin.initializeApp({
    credential: admin.credential.cert(
      require(path.join(__dirname, "./serviceAccountKey.json"))
    ),
  });
}
const db = admin.firestore();
console.log("✅ Firestore initialized successfully");

const transporter = nodemailer.createTransport({
  service: "gmail",
  auth: {
    user: process.env.EMAIL_USER,
    pass: process.env.EMAIL_PASS,
  },
});

const googleClient = new OAuth2Client(process.env.GOOGLE_CLIENT_ID);

interface OtpEntry {
  code: string;
  createdAt: number;
  purpose: "register" | "reset";
}
const otpStore: { [email: string]: OtpEntry } = {};

setInterval(() => {
  const now = Date.now();
  const expiryMs = 3 * 60 * 1000;
  let removed = 0;
  for (const email in otpStore) {
    if (now - otpStore[email].createdAt > expiryMs) {
      delete otpStore[email];
      removed++;
    }
  }
  if (removed > 0) console.log(`🧹 Cleaned ${removed} expired OTP(s)`);
}, 60 * 1000);

app.get("/", (req, res) => {
  console.log("📡 GET / - Backend online");
  return res.send("✅ Expressora Backend Running");
});

app.post("/reg-send-otp", async (req: Request, res: Response) => {
  const { email } = req.body;
  if (!email)
    return res.status(400).json({ success: false, message: "Email is required" });

  try {
    const existingUser = await db.collection("users").where("email", "==", email).get();
    if (!existingUser.empty) {
      return res.status(409).json({
        success: false,
        message: "Email already registered. Please log in instead.",
      });
    }

    const otp = Math.floor(10000 + Math.random() * 90000).toString();
    otpStore[email] = { code: otp, createdAt: Date.now(), purpose: "register" };
    console.log(`🔐 [REG] OTP for ${email}: ${otp}`);

    await transporter.sendMail({
        from: `"Expressora" <${process.env.EMAIL_USER}>`,
        to: email,
        subject: "Expressora | Email Verification Code",
        html: `
       <div style="font-family:'Inter',Arial,sans-serif; background-color:#f8fafc; padding:48px 24px; text-align:center;">
         <div style="max-width:720px; margin:auto; background:white; border-radius:20px; padding:44px 40px; box-shadow:0 8px 24px rgba(0,0,0,0.06); border:1px solid #f1f1f1;">
           <img src="https://res.cloudinary.com/dugthtx3b/image/upload/v1761753085/expressora_logo_emnorx.png"
                alt="Expressora Logo"
                width="80"
                style="margin-bottom:20px;">
           <h2 style="color:#111827; font-weight:700; font-size:22px; margin-bottom:8px;">Verify Your Email</h2>
           <p style="color:#374151; font-size:15px; line-height:1.6; margin-bottom:20px;">
             Welcome to <strong>Expressora</strong>!<br>
             Please use the verification code below to confirm your email address.
           </p>

           <div style="display:inline-block; background:linear-gradient(135deg, #FACC15, #FFD84D); color:#111; font-weight:700; font-size:24px; padding:12px 28px; border-radius:10px; letter-spacing:3px; margin:8px 0 10px; box-shadow:0 3px 8px rgba(250,204,21,0.25);">
             ${otp}
           </div>

           <p style="color:#6b7280; font-size:14px; margin-top:8px;">
             This code will expire in <strong>3 minutes</strong>.
           </p>

           <hr style="margin:32px 0; border:none; border-top:1px solid #e5e7eb;">

           <p style="color:#9ca3af; font-size:13px; line-height:1.5;">
             Didn’t request this verification?<br>
             You can safely ignore this email.
           </p>

           <p style="color:#bdbdbd; font-size:12px; margin-top:24px;">
             &copy; ${new Date().getFullYear()} Expressora. All rights reserved.
           </p>
         </div>
       </div>
    `,
      });

    return res.json({ success: true, message: "OTP sent successfully" });
  } catch (error) {
    console.error("❌ /reg-send-otp error:", error);
    return res.status(500).json({ success: false, message: "Failed to send OTP" });
  }
});

app.post("/reg-verify-otp", (req: Request, res: Response) => {
  const { email, otp } = req.body;
  if (!email || !otp)
    return res.status(400).json({ success: false, message: "Email and OTP required" });

  const entry = otpStore[email];
  if (!entry || entry.purpose !== "register")
    return res.status(400).json({ success: false, message: "OTP not found or expired" });

  if (Date.now() - entry.createdAt > 3 * 60 * 1000) {
    delete otpStore[email];
    return res.status(400).json({ success: false, message: "OTP expired" });
  }

  if (entry.code === otp) {
    delete otpStore[email];
    console.log(`✅ [REG] OTP verified for ${email}`);
    return res.json({ success: true, message: "OTP verified successfully" });
  } else {
    return res.status(400).json({ success: false, message: "Invalid OTP" });
  }
});

app.post("/reset-send-otp", async (req: Request, res: Response) => {
  const { email } = req.body;
  if (!email)
    return res.status(400).json({ success: false, message: "Email is required" });

  try {
    const userSnap = await db.collection("users").where("email", "==", email).get();
    if (userSnap.empty) {
      return res.status(404).json({
        success: false,
        message: "Email not found. Please register first.",
      });
    }

    const otp = Math.floor(10000 + Math.random() * 90000).toString();
    otpStore[email] = { code: otp, createdAt: Date.now(), purpose: "reset" };
    console.log(`🔐 [RESET] OTP for ${email}: ${otp}`);

    await transporter.sendMail({
        from: `"Expressora Support" <${process.env.EMAIL_USER}>`,
        to: email,
        subject: "Expressora | Password Reset Code",
        html: `
        <div style="font-family:'Inter',Arial,sans-serif; background-color:#f8fafc; padding:48px 24px; text-align:center;">
          <div style="max-width:720px; margin:auto; background:white; border-radius:20px; padding:44px 40px; box-shadow:0 8px 24px rgba(0,0,0,0.06); border:1px solid #f1f1f1;">
            <img src="https://res.cloudinary.com/dugthtx3b/image/upload/v1761753085/expressora_logo_emnorx.png"
                 alt="Expressora Logo"
                 width="80"
                 style="margin-bottom:20px;">
            <h2 style="color:#111827; font-weight:700; font-size:22px; margin-bottom:8px;">Reset Your Password</h2>
            <p style="color:#374151; font-size:15px; line-height:1.6; margin-bottom:20px;">
              We received a request to reset your <strong>Expressora</strong> account password.<br>
              Please use the verification code below to continue.
            </p>

            <div style="display:inline-block; background:linear-gradient(135deg, #FACC15, #FFD84D); color:#111; font-weight:700; font-size:24px; padding:12px 28px; border-radius:10px; letter-spacing:3px; margin:8px 0 10px; box-shadow:0 3px 8px rgba(250,204,21,0.25);">
              ${otp}
            </div>

            <p style="color:#6b7280; font-size:14px; margin-top:8px;">
              This code will expire in <strong>3 minutes</strong>.
            </p>

            <hr style="margin:32px 0; border:none; border-top:1px solid #e5e7eb;">

            <p style="color:#9ca3af; font-size:13px; line-height:1.5;">
              Didn’t request a password reset?<br>
              You can safely ignore this email.
            </p>

            <p style="color:#bdbdbd; font-size:12px; margin-top:24px;">
              &copy; ${new Date().getFullYear()} Expressora. All rights reserved.
            </p>
          </div>
        </div>
        `,
      });

    return res.json({ success: true, message: "Reset OTP sent successfully" });
  } catch (error) {
    console.error("❌ /reset-send-otp error:", error);
    return res.status(500).json({ success: false, message: "Failed to send reset OTP" });
  }
});

app.post("/reset-verify-otp", (req: Request, res: Response) => {
  const { email, otp } = req.body;
  if (!email || !otp)
    return res.status(400).json({ success: false, message: "Email and OTP required" });

  const entry = otpStore[email];
  if (!entry || entry.purpose !== "reset")
    return res.status(400).json({ success: false, message: "OTP not found or expired" });

  if (Date.now() - entry.createdAt > 3 * 60 * 1000) {
    delete otpStore[email];
    return res.status(400).json({ success: false, message: "OTP expired" });
  }

  if (entry.code === otp) {
    console.log(`✅ [RESET] OTP verified for ${email}`);
    return res.json({ success: true, message: "OTP verified successfully" });
  } else {
    console.log(`❌ [RESET] Invalid OTP for ${email}`);
    return res.status(400).json({ success: false, message: "Invalid OTP" });
  }
});

app.post("/reset-password", async (req: Request, res: Response) => {
  const { email, newPassword } = req.body;
  if (!email || !newPassword)
    return res.status(400).json({ success: false, message: "Email and password required" });

  try {
    const userSnap = await db.collection("users").where("email", "==", email).get();
    if (userSnap.empty)
      return res.status(404).json({ success: false, message: "User not found" });

    const docId = userSnap.docs[0].id;
    await db.collection("users").doc(docId).update({ password: newPassword });

    console.log(`🔑 Password reset for ${email}`);
    return res.json({ success: true, message: "Password reset successful" });
  } catch (error) {
    console.error("❌ /reset-password error:", error);
    return res.status(500).json({ success: false, message: "Failed to reset password" });
  }
});

app.post("/google-auth", async (req: Request, res: Response) => {
  const { token } = req.body;
  if (!token)
    return res.status(400).json({ success: false, message: "Token required" });

  try {
    const ticket = await googleClient.verifyIdToken({
      idToken: token,
      audience: process.env.GOOGLE_CLIENT_ID,
    });
    const payload = ticket.getPayload();
    if (!payload) throw new Error("Invalid Google token");

    const { email, name, picture } = payload;
    console.log(`✅ Google verified user: ${email}`);
    return res.status(200).json({ success: true, user: { email, name, picture } });
  } catch (error: any) {
    console.error("❌ Google auth failed:", error.message);
    return res.status(401).json({ success: false, message: error.message });
  }
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`🚀 Expressora Server running at http://localhost:${PORT}`);
});

