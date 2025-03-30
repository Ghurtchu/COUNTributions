document.addEventListener("DOMContentLoaded", () => {
      populateEmptyRows(); // Populate empty rows on load
});

async function fetchContributors() {
  const orgName = document.getElementById("org-name").value.trim();
  if (!orgName) {
    alert("Please enter an organization name.");
    return;
  }

  const startTime = Date.now(); // Capture start time

  document.getElementById("loading").style.display = "block";

  try {
    const response = await fetch(`http://localhost:9000/org/${orgName}`);
    const data = await response.json();

    const endTime = Date.now(); // Capture end time
    const requestTime = endTime - startTime; // Calculate the time difference

    document.getElementById("loading").style.display = "none";
    document.getElementById(
      "request-time"
    ).textContent = `latency: ${requestTime} ms`;

    if (data.count === 0) {
      alert("No contributors found.");
      populateEmptyRows();
      return;
    }

    populateTable(data.contributors);
  } catch (error) {
    const endTime = Date.now();
    const requestTime = endTime - startTime;
    document.getElementById("loading").style.display = "none";
    document.getElementById(
      "request-time"
    ).textContent = `latency: ${requestTime} ms`;
    alert("An error occurred while fetching the data.");
    populateEmptyRows();
  }
}

function populateTable(contributors) {
  const contributorsBody = document.getElementById("contributors-body");
  contributorsBody.innerHTML = "";

  let totalContributions = 0;

  contributors.forEach((contributor) => {
    totalContributions += contributor.contributions;
    const row = document.createElement("tr");
    row.setAttribute("data-name", contributor.login.toLowerCase()); // Set data attribute for filtering
    row.innerHTML = `
    <td><a href="https://github.com/${contributor.login}" target="_blank">${contributor.login}</a></td>
    <td>${contributor.contributions}</td>
  `;
    contributorsBody.appendChild(row);
  });

  document.getElementById("total-contributors").textContent =
    contributors.length;
  document.getElementById("total-contributions").textContent =
    totalContributions;

  if (contributors.length < 8) {
    addEmptyRows(8 - contributors.length);
  }
}

function populateEmptyRows() {
  const contributorsBody = document.getElementById("contributors-body");
  contributorsBody.innerHTML = "";
  addEmptyRows(8);
  document.getElementById("total-contributors").textContent = "0";
  document.getElementById("total-contributions").textContent = "0";
}

function addEmptyRows(count) {
  for (let i = 0; i < count; i++) {
    const row = document.createElement("tr");
    row.innerHTML = `<td class="loading-placeholder"> - </td><td class="loading-placeholder"> - </td>`;
    document.getElementById("contributors-body").appendChild(row);
  }
}

function clearInput() {
  document.getElementById("org-name").value = "";
  toggleClearButton();
}

function toggleClearButton() {
  document.querySelector(".clear-btn").style.display =
    document.getElementById("org-name").value ? "block" : "none";
}

function filterTable() {
  const searchQuery = document
    .getElementById("search-input")
    .value.toLowerCase();
  const rows = document.querySelectorAll("#contributors-body tr");

  rows.forEach((row) => {
    const contributorName = row.getAttribute("data-name");
    if (contributorName.includes(searchQuery)) {
      row.style.display = "";
    } else {
      row.style.display = "none";
    }
  });
}

document
  .getElementById("submit-btn")
  .addEventListener("click", fetchContributors);
document
  .getElementById("clear-btn")
  .addEventListener("click", populateEmptyRows);