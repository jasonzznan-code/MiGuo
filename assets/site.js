(function () {
  var buttons = document.querySelectorAll("button[data-copy]");
  buttons.forEach(function (button) {
    button.addEventListener("click", function () {
      var value = button.getAttribute("data-copy") || "";
      var status = document.getElementById(button.getAttribute("aria-controls"));
      writeClipboard(value).then(
        function () {
          if (status) status.textContent = "已复制 SHA-256";
        },
        function () {
          if (status) status.textContent = "没有写入剪贴板。请选中上方校验值，手动复制。";
          var code = document.getElementById("sha256");
          if (!code || !window.getSelection) return;
          var range = document.createRange();
          range.selectNodeContents(code);
          var selection = window.getSelection();
          selection.removeAllRanges();
          selection.addRange(range);
        }
      );
    });
  });

  function fallbackCopy(value) {
    return new Promise(function (resolve, reject) {
      var area = document.createElement("textarea");
      area.value = value;
      area.setAttribute("readonly", "");
      area.style.position = "fixed";
      area.style.left = "-9999px";
      document.body.appendChild(area);
      area.select();
      var ok = false;
      try {
        ok = document.execCommand("copy");
      } catch (err) {
        ok = false;
      }
      document.body.removeChild(area);
      if (ok) resolve();
      else reject(new Error("copy failed"));
    });
  }

  function writeClipboard(value) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(value).catch(function () {
        return fallbackCopy(value);
      });
    }
    return fallbackCopy(value);
  }
})();
