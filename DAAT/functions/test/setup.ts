// The Admin SDK reads its project and storage bucket from these.
process.env.GCLOUD_PROJECT ??= "demo-hitlist";
process.env.FIREBASE_CONFIG ??= JSON.stringify({
  projectId: "demo-hitlist",
  storageBucket: "demo-hitlist.appspot.com",
});
